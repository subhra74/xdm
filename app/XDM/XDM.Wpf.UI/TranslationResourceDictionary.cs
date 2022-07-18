using System;
using System.Collections.Generic;
using System.Linq;
using System.Text;
using System.Windows;
using Translations;
using XDM.Core.Lib.Common;

namespace XDM.Wpf.UI
{
    internal class TranslationResourceDictionary : ResourceDictionary
    {
        public TranslationResourceDictionary()
        {
            var indexFile = System.IO.Path.Combine(AppDomain.CurrentDomain.BaseDirectory, @"Lang\index.txt");
            if (System.IO.File.Exists(indexFile))
            {
                var lines = System.IO.File.ReadAllLines(indexFile);
                foreach (var line in lines)
                {
                    var index = line.IndexOf("=");
                    if (index > 0)
                    {
                        var name = line.Substring(0, index);
                        var value = line.Substring(index + 1);
                        if (name == Config.Instance.Language)
                        {
                            TextResource.Load(value);
                            foreach (var key in TextResource.GetKeys())
                            {
                                Add(key, TextResource.GetText(key));
                            }
                        }
                    }
                }
            }

        }
    }
}
