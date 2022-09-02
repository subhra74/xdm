#!/usr/bin/python3

import json
import os
import struct
import subprocess
import sys
import socket
import base64
import threading
import time
from types import int, List

MESSAGE_HEADER = "---XDM-MESSAGE-START---"
MESSAGE_FOOTER = "---XDM-MESSAGE-END---"


class TcpClient:
    def __init__(self) -> None:
        self.socket = None

    def connect(self, port: int) -> None:
        self.socket = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        self.socket.connect(("127.0.0.1", port))

    def send(self, args: List[str]) -> None:
        self.socket.sendall(MESSAGE_HEADER+"\n")
        for arg in args:
            self.socket.sendall(TcpClient.encode_to_base64(arg)+"\n")
        self.socket.sendall(MESSAGE_FOOTER+"\n")

    def recv(self) -> List[str]:
        args = list()
        if self.read_line() == MESSAGE_HEADER:
            while True:
                line = self.read_line()
                if line == MESSAGE_FOOTER:
                    return args
                args.append(TcpClient.decode_from_base64(line))
        return ""

    def read_line(self) -> str:
        line = ""
        data = self.socket.recv(1)
        if not data:
            raise IOError("Unexpected EOF")
        if data[0] == '\n':
            return line
        if data[0] != '\r':
            line += data[0]

    def close(self):
        if self.socket:
            self.socket.close()

    @staticmethod
    def encode_to_base64(message: str) -> bytes:
        b = message.encode()
        return base64.b64encode(b)

    @staticmethod
    def decode_from_base64(message: str) -> str:
        return base64.b64decode(message).decode('utf-8')


class MessagingHost:
    def __init__(self) -> None:
        self.socket = TcpClient()

    def start_xdm(self):
        exe = os.path.abspath(os.path.join(os.path.dirname(
            os.path.realpath(__file__)), "..", "xdm-app"))
        subprocess.Popen(executable=exe, start_new_session=True)

    def run(self) -> None:
        try:
            self.socket.connect(8597)
        except:
            sys.stderr.write(
                'Unable to connect to XDM host, trying to launch XDM')
            self.start_xdm()
            time.sleep(1)
        connected = False
        for _ in range(5):
            try:
                self.socket.connect(8597)
                connected = True
                break
            except:
                time.sleep(1)
        if not connected:
            sys.stderr.write(
                'Unable to connect to XDM host after 5 retry, giving up')

        self.thread = threading.Thread(self.receive_config_message)
        self.thread.start()

        while True:
            message = self.read_message_bytes()
            self.send_args_to_xdm(message)

    def send_args_to_xdm(self, message) -> None:
        if not message:
            return
        args = list()
        if message.cookie:
            args.append("--cookie")
            args.append(message.cookie)
        if message.headers:
            for header in message.headers:
                args.append("-H")
                args.append(header)
        if message.file_size:
            args.append("--known-file-size")
            args.append(message.file_size)
        if message.mime_type:
            args.append("--known-mime-type")
            args.append(message.mime_type)
        if message.file_name:
            args.append("--output")
            args.append(message.file_name)
        args.append(message.url)
        self.socket.send(args)

    def read_message_bytes(self) -> None:
        raw_length = sys.stdin.read(4)
        if not raw_length:
            sys.exit(0)
        message_length = struct.unpack('=I', raw_length)[0]
        message = sys.stdin.read(message_length)
        return json.loads(message)

    def receive_config_message(self) -> None:
        try:
            while True:
                lines = self.socket.recv()
                message = "\n".join(lines)
                encoded_length = struct.pack('=I', len(message))
                sys.stdout.write(encoded_length)
                sys.stdout.write(message)
                sys.stdout.flush()
        except:
            sys.stdout.write('Error receiving data from XDM')
            os._exit(1)
