using System;
using System.IO.Pipes;
using System.Threading;
using TraceLog;

namespace XDM.Core.BrowserMonitoring
{
    internal sealed class NativeMessagingHostChannel
    {
        private NamedPipeServerStream pipe;
        internal event EventHandler<NativeMessageEventArgs>? MessageReceived;
        internal event EventHandler? Disconnected;
        private Thread readerThread;

        internal NativeMessagingHostChannel(NamedPipeServerStream pipe)
        {
            this.pipe = pipe;
            readerThread = new Thread(() =>
            {
                try
                {
                    ReadDataFromPipe();
                }
                catch (Exception ex)
                {
                    Log.Debug(ex, ex.ToString());
                    Disconnect();
                }
                this.Disconnected?.Invoke(this, EventArgs.Empty);
            });
        }

        internal void Disconnect()
        {
            try
            {
                if (pipe.IsConnected)
                {
                    pipe.Disconnect();
                }
            }
            catch (Exception ex)
            {
                Log.Debug(ex, ex.ToString());
            }
        }

        internal void Publish(byte[] data)
        {
            try
            {
                NativeMessageSerializer.WriteMessage(pipe, data);
                pipe.Flush();
            }
            catch (Exception ex)
            {
                Log.Debug(ex, ex.Message);
            }
        }

        internal void ReadDataFromPipe()
        {
            while (true)
            {
                Log.Debug("Waiting for message from native host...");
                var bytes = NativeMessageSerializer.ReadMessageBytes(pipe);
                Log.Debug($"Message from native host {bytes.Length} bytes");
                this.MessageReceived?.Invoke(this, new NativeMessageEventArgs(bytes));
            }
        }

        internal void Start(byte[] initialConfig)
        {
            try
            {
                readerThread.Start();
                NativeMessageSerializer.WriteMessage(pipe, initialConfig);
            }
            catch (Exception ex)
            {
                Log.Debug(ex, ex.Message);
            }
        }
    }

    internal class NativeMessageEventArgs : EventArgs
    {
        private byte[] data;

        internal NativeMessageEventArgs(byte[] data)
        {
            this.data = data;
        }

        internal byte[] Data => data;
    }
}
