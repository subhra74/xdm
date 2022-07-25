using System;
using System.Collections.Generic;
using System.Linq;
using System.Text;
using XDM.Core.Lib.Common;

namespace XDM.Core.Lib.Common
{
    public static class ArgsProcessor
    {
        public static void Process(IAppService app, Dictionary<string, string?> args)
        {
            if (args.ContainsKey("-u"))
            {
                var url = args["-u"];
                if (!string.IsNullOrEmpty(url))
                {
                    app.AddDownload(new Message { Url = url! });
                }
            }
        }

        public static Dictionary<string, string?> ParseArgs(string[] args, int start = 0)
        {
            var options = new Dictionary<string, string?>();
            var key = string.Empty;
            for (int i = start; i < args.Length; i++)
            {
                var arg = args[i];
                if (key != string.Empty)
                {
                    options[key] = arg;
                    key = string.Empty;
                }
                else
                {
                    key = arg;
                    options[key] = null;
                }
            }
            return options;
        }
    }
}
