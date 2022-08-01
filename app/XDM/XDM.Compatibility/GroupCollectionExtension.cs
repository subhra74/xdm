#if !NET5_0_OR_GREATER

using System.Text.RegularExpressions;

namespace XDM.Compatibility
{
    public static class GroupCollectionExtension
    {
        public static bool ContainsKey(this GroupCollection collection, string key)
        {
            return collection[key].Success;
        }
    }
}

#endif
