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
            TextResource.Load(Config.Instance.Language);
            foreach (var key in TextResource.GetKeys())
            {
                Add(key, TextResource.GetText(key));
            }
        }
    }
}
