using System;
using System.Collections.Generic;
using System.IO;
using System.Text;

namespace XDM.Core.Util
{
    public static class ExtensionRegistrationHelper
    {
        public static void AddExtension(string extension)
        {
            var file = Path.Combine(Config.AppDir, "extension.txt");
            var extensions = new List<string>();
            if (File.Exists(file))
            {
                extensions.AddRange(File.ReadAllLines(file));
            }
            extensions.Add(extension);
            File.WriteAllLines(file, extensions);
        }
    }
}
