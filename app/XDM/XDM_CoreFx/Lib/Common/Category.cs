using System;
using System.Collections.Generic;
using System.Linq;
using System.Text;

namespace XDM.Core.Lib.Common
{
    public struct Category
    {
        public string Name { get; set; }
        public HashSet<string> FileExtensions { get; set; }
        public string DisplayName { get; set; }
        public bool IsPredefined { get; set; }
        public string DefaultFolder { get; set; }
        public override string ToString()
        {
            return DisplayName;
        }
    }
}
