package main

import (
	"os"
	"path/filepath"
	"syscall"
)

//SpawnProcess creates a process
func SpawnProcess() {
	var sI syscall.StartupInfo
	var pI syscall.ProcessInformation

	//argv := syscall.StringToUTF16Ptr("c:\\windows\\system32\\notepad.exe")

	flags := uint32(syscall.CREATE_UNICODE_ENVIRONMENT)
	flags |= CreateBreakAwayFromJob
	flags |= CreateNewProcessGroup

	path,err:=os.Executable()

	if err==nil{
		return
	}

	path=filepath.Dir(path)

	 syscall.CreateProcess(
		nil,//syscall.StringToUTF16Ptr(""),
		syscall.StringToUTF16Ptr("\""+path+"\\jre\\bin\\javaw.exe\" -jar \""+path+"\\xdman.jar\" -m"),
		nil,
		nil,
		false,
		flags,
		nil,
		nil,
		&sI,
		&pI)
	
}