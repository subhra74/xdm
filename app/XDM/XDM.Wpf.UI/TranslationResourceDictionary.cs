using System;
using System.Collections.Generic;
using System.Linq;
using System.Text;
using System.Windows;
using Translations;

namespace XDM.Wpf.UI
{
    internal class TranslationResourceDictionary : ResourceDictionary
    {
        public TranslationResourceDictionary()
        {
            foreach (var key in TextResource.GetKeys())
            {
                Add(key, TextResource.GetText(key));
            }
        }
    }
}
