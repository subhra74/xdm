package main

import (
	"os"
	"os/exec"
	"path/filepath"
)

//SpawnProcess creates a process
func SpawnProcess() {

	path,err:=os.Executable()

	if err==nil{
		return
	}

	path=filepath.Dir(path)

	exec.Command(path+"/jre/bin/java","-jar",path+"/xdman.jar").Start()
}