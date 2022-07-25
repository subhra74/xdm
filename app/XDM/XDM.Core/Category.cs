using System.Collections.Generic;

namespace XDM.Core
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
