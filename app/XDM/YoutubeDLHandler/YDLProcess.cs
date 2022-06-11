using System;
using System.Diagnostics;
using System.IO;
using System.Text;
using TraceLog;
using XDM.Core.Lib.Common;
using XDM.Core.Lib.Util;

namespace YDLWrapper
{
    public class YDLProcess
    {
        public Uri? Uri { get; set; }
        public string? UserName { get; set; }
        public string? Password { get; set; }
        public string? JsonOutputFile { get; set; }
        public string? BrowserName { get; set; } //Fetch cookies from browser

        private Process? ydlProc;

        public void Cancel()
        {
            if (ydlProc != null)
            {
                try
                {
                    ydlProc.Kill();
                }
                catch
                {
                }
            }
        }

        public void Start()
        {
            var exec = Helpers.FindYDLBinary();
            var pb = new ProcessStartInfo
            {
                FileName = exec,
            };

            var sb = new StringBuilder();
            foreach (var arg in new string[] { "--no-warnings", "-q", "-i", "-J",
                string.IsNullOrEmpty(BrowserName)?string.Empty:$"--cookies-from-browser {BrowserName}",
                Uri!.ToString() })
            {
                sb.Append(" " + arg);
            }

            if (!string.IsNullOrEmpty(UserName))
            {
                sb.Append(" --username ").Append(UserName);
                if (!string.IsNullOrEmpty(Password))
                {
                    sb.Append(" --password ").Append(Password);
                }
            }

            pb.Arguments = sb.ToString();
            pb.RedirectStandardOutput = true;
            pb.CreateNoWindow = true;
            pb.UseShellExecute = false;
            pb.RedirectStandardError = true;
            pb.RedirectStandardInput = false;
            pb.StandardOutputEncoding = Encoding.UTF8;
            JsonOutputFile = Path.Combine(Path.GetTempPath(), Guid.NewGuid() + ".json");
            Log.Debug("Opening youtube-dl json file: " + JsonOutputFile);
            using var fs = new FileStream(JsonOutputFile,
                FileMode.Create, FileAccess.ReadWrite);

            try
            {
                ydlProc = Process.Start(pb);
                ydlProc.OutputDataReceived += (a, b) =>
                {
                    if (b.Data != null)
                    {
                        var bytes = Encoding.UTF8.GetBytes(b.Data);
                        //Console.WriteLine(b.Data);
                        fs.Write(bytes, 0, bytes.Length);
                    }
                };
                ydlProc.ErrorDataReceived += (a, b) =>
                {
                    if (b.Data != null)
                    {
                        Log.Debug(b.Data);
                    }
                };

                ydlProc.BeginOutputReadLine();

                ydlProc.WaitForExit();
                fs.Close();

                var exitCode = ydlProc.ExitCode;

                if (ydlProc.ExitCode != 0)
                {
                    Log.Debug("Non-zero error code from youtube-dl: " + ydlProc.ExitCode);
                    throw new Exception("Non-zero error code from youtube-dl: " + ydlProc.ExitCode);
                }
            }
            finally
            {
                ydlProc?.Dispose();
                ydlProc = null;
            }
        }

        //private void ProcessJson()
        //{
        //    using (StreamReader reader = File.OpenText(@"C:\Users\subhro\Desktop\80a44682-5ea8-4193-bc52-34ee568ce9bb.json"/*JsonOutputFile*/))
        //    {
        //        JObject o = (JObject)JToken.ReadFrom(new JsonTextReader(reader));
        //        o[]
        //    }
        //}
    }
}
