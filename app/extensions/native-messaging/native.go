// +build windows

package main

import (
	"bytes"
	"encoding/base64"
	"encoding/binary"
	"encoding/json"
	"io"
	"io/ioutil"
	"net/http"
	"os"
	"path"
	"strconv"
	"strings"
	"time"
)

var log *os.File

//NativeMessage serialized message structure
type NativeMessage struct{
	message string
}

//VidListItem serialized message structure
type VidListItem struct{
	ID 		string `json:"id"`
	Text 	string `json:"text"`
	Info 	string `json:"info"`
}

//XDMNotification serialized message structure
type XDMNotification struct{
	Enabled 		bool			`json:"enabled"`
	BlockedHosts 	[]string		`json:"blockedHosts"`
	VideoUrls 		[]string		`json:"videoUrls"`
	FileExts 		[]string		`json:"fileExts"`
	VidExts 		[]string		`json:"vidExts"`
	MimeList 		[]string		`json:"mimeList"`
	VidList 		[]VidListItem	`json:"vidList"`
}


var lastChanged time.Time

//CreateBreakAwayFromJob constant for win32 flag CREATE_BREAKAWAY_FROM_JOB
const CreateBreakAwayFromJob uint32 = 0x01000000

//CreateNewProcessGroup constant for win32 flag CREATE_NEW_PROCESS_GROUP
const CreateNewProcessGroup uint32 = 0x01000000

func readInt32() int64 {
	log.WriteString("reading chunk size\n")
	var chunkSize int32
	in := make([]byte, 4)
	c, err := os.Stdin.Read(in)
	if c != 4 || err != nil {
		panic("Invalid chunk size")
	}
	err = binary.Read(bytes.NewBuffer(in), binary.LittleEndian, &chunkSize)
	if err != nil {
		panic("could not read chunk size")
	}
	log.WriteString("chunk size: " + strconv.FormatInt(int64(chunkSize), 10) + "\n")
	return int64(chunkSize)
}

func writeInt32(n int) {
	buf := new(bytes.Buffer)
	//out:=make([]byte,4)
	n32 := int32(n)
	log.WriteString("message payload2: " + strconv.FormatInt(int64(n32), 10) + "\n")

	binary.Write(buf, binary.LittleEndian, n32)
	os.Stdout.Write(buf.Bytes())
}

func readNativeMessage() string {
	chunkSize := readInt32()
	buf := make([]byte, chunkSize)
	c, err := io.ReadFull(os.Stdin, buf)
	log.WriteString("message size: " + strconv.FormatInt(int64(c), 10) + "\n")

	if err != nil {
		panic("Error reading message body")
	}
	log.WriteString("Raw buffer\n")
	log.Write(buf)

	var dat map[string]interface{}

	json.Unmarshal(buf,&dat)
	log.WriteString("\nParsed message: "+dat["message"].(string)+"\n")

	str := dat["message"].(string)
	log.WriteString("message read: " + str + "\n" + strconv.FormatInt(int64(len(str)), 10))

	return str
}

func writeNativeMessage(message string) {
	buf := []byte(message)
	log.WriteString("message payload: " + strconv.FormatInt(int64(len(buf)), 10) + "\n")

	writeInt32(len(buf))
	os.Stdout.Write(buf)
}

/*func spawnProcess() {
	var sI syscall.StartupInfo
	var pI syscall.ProcessInformation

	argv := syscall.StringToUTF16Ptr("c:\\windows\\system32\\notepad.exe")

	flags := uint32(syscall.CREATE_UNICODE_ENVIRONMENT)
	flags |= CreateBreakAwayFromJob
	flags |= CreateNewProcessGroup

	err := syscall.CreateProcess(
		nil,
		argv,
		nil,
		nil,
		false,
		flags,
		nil,
		nil,
		&sI,
		&pI)
	if err != nil {
		log.WriteString("Error while creating process")
	}
}
*/
func createOrOpenXDM(message string){
	log.WriteString("Processing message: "+message+"\n");
	valid:=false
	var header string
	if len(message)>0{
		lines:=strings.Split(message,"\r\n")
		header=lines[0]
		log.WriteString("Received header: '"+header+"'\r\n");
		if header=="/download"||header=="/video"||header=="/quit" ||
			header=="/cmd"||header=="/preview"||header=="/links"||
			header=="/item"||header=="/clear"{
				valid=true
		}
	}
	if(!valid){
		log.WriteString("Received header: "+header+" is not valid\n");
		return
	}
	processCreated:=false
	for i:=0;i<60;i++{
		resp, err := http.Post("http://127.0.0.1:9614"+header, "text/plain", bytes.NewBuffer([]byte(message)))
		if err == nil{
			ioutil.ReadAll(resp.Body)
			resp.Body.Close()
			return
		}
		if !processCreated{
			SpawnProcess()
			processCreated = true
		}
		time.Sleep(500 * time.Millisecond)
	}
}

func parseVideoList(text string)[]VidListItem{
	list:=[]VidListItem{};
	if len(text)>0{
		vidList:=strings.Split(text,",")
		for _, str := range vidList {
			fields:=strings.Split(str,"|")
			id,err:= base64.StdEncoding.DecodeString(fields[0])
			if err!=nil{
				continue
			}
			text,err:= base64.StdEncoding.DecodeString(fields[1])
			if err!=nil{
				continue
			}
			info,err:= base64.StdEncoding.DecodeString(fields[2])
			if err!=nil{
				continue
			}
			list=append(list,VidListItem{string(id),string(text),string(info)})
		}
	}
	return list
}

//ParseMessage parse XDM message
func ParseMessage(bytes []byte)XDMNotification{
	message:=XDMNotification{}
	text:=string(bytes)
	for _, str := range strings.Split(text,"\n") {
		line:=strings.TrimSpace(str)
		if len(line)>0{
			pair:=strings.Split(line,":")
			if len(pair)==2{
				key:=strings.TrimSpace(pair[0])
				val:=strings.TrimSpace(pair[1])
				switch key{
				case "enabled":
					message.Enabled=(val=="true")
				case "blockedHosts":
					message.BlockedHosts=strings.Split(val,",")
				case "videoUrls":
					message.VideoUrls=strings.Split(val,",")
				case "fileExts":
					message.FileExts=strings.Split(val,",")
				case "vidExts":
					message.VidExts=strings.Split(val,",")
				case "mimeList":
					message.MimeList=strings.Split(val,",")
				case "vidList":
					message.VidList=parseVideoList(val)
				}
			}
		}
	}
	return message
}

func loadSettings(settingsFile string){
	fp,err:=os.Open(settingsFile)
	if err==nil{
		buf,err:=ioutil.ReadAll(fp)
		if err==nil{
			message:=ParseMessage(buf)
			jsonBytes,err:=json.Marshal(message)
			if err==nil{
				writeNativeMessage(string(jsonBytes))
			}
		}
	}
}

func watchForSettingsChange(){
	userHome,err:=os.UserHomeDir()
	if err!=nil{
		log.WriteString("Unble to get user home!\n")
		panic("Unble to get user home!\n")
	}
	keyFile:=path.Join(userHome,".xdman","settings_updated")
	settingsFile:=path.Join(userHome,".xdman","settings.json")

	loadSettings(settingsFile)
	lastChanged=time.Now()

	for{
		stat,error:=os.Stat(keyFile)
		if error==nil && stat.ModTime().After(lastChanged){
			loadSettings(settingsFile)
			lastChanged=time.Now()
		}
		time.Sleep(1000*time.Millisecond)
	}
}

func main() {

	log, _ = os.Create("C:\\Users\\subhro\\Documents\\go-projects\\native-messaging\\log.txt")
	go watchForSettingsChange()
	for {
		message := readNativeMessage()
		createOrOpenXDM(message)
		//writeNativeMessage(message)
	}
}
