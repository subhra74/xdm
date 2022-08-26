using System;
using System.Collections.Generic;
using System.IO;
using System.Net;
using System.Net.Sockets;
using System.Text;

namespace NativeMessaging
{
    public class IpcClient
    {
        private TcpClient? _socket;
        private Stream? _socketIn;
        private Stream? _socketOut;

        public void Connect(int port)
        {
            _socket = new TcpClient(AddressFamily.InterNetwork);
            _socket.Connect(new IPEndPoint(IPAddress.Loopback, port));
            _socketIn = _socket.GetStream();
            _socketOut = _socket.GetStream();
        }

        public void Send(IEnumerable<string> args)
        {
            IpcUtil.Send(_socketOut!, args);
        }

        public IEnumerable<string> Receive()
        {
            return IpcUtil.Receive(_socketIn!);
        }

        public void Close()
        {
            try { _socket?.Close(); } catch { }
        }
    }
}
