using System;
using System.Collections.Generic;
using System.Linq;
using System.Text;
using System.Threading.Tasks;
using System.Security.Cryptography;
using System.IO;
using XDM.Core.Lib.Common;
using System.Threading;
using XDM.Core.Lib.Downloader;

namespace XDM.SystemTests
{
    internal static class TestUtil
    {
        internal static string GetFileHash(string file)
        {
            using SHA256 sha256Hash = SHA256.Create();
            using FileStream fs = new FileStream(file, FileMode.Open);

            byte[] buf = new byte[8192];
            while (true)
            {
                int x = fs.Read(buf, 0, buf.Length);
                if (x == 0) break;
                sha256Hash.TransformBlock(buf, 0, x, null, 0);
            }
            sha256Hash.TransformFinalBlock(new byte[0] { }, 0, 0);

            byte[] bytes = sha256Hash.Hash;
            StringBuilder builder = new StringBuilder();
            for (int i = 0; i < bytes.Length; i++)
            {
                builder.Append(bytes[i].ToString("x2"));
            }
            return builder.ToString();
        }

        internal static async Task<bool> WaitForDownloadResult(IBaseDownloader downloader)
        {
            var success = false;
            var cs = new CancellationTokenSource();
            downloader.Finished += (a, b) =>
            {
                success = true;
                cs.Cancel();
            };
            downloader.Failed += (a, b) =>
            {
                success = false;
                cs.Cancel();
            };

            downloader.Start();

            try
            {
                await Task.Delay(Int32.MaxValue, cs.Token);
            }
            catch { }

            return success;
        }
    }
}
