#if !NET5_0_OR_GREATER

using System.Collections.Generic;

namespace NetFX.Polyfill
{
    public static class DictionaryExtensions
    {
        public static V GetValueOrDefault<K, V>(this Dictionary<K, V> dict, K key, V defaultValue)
        {
            if (dict.TryGetValue(key, out V value))
            {
                return value;
            }
            return defaultValue;
        }

        public static V GetValueOrDefault<K, V>(this Dictionary<K, V> dict, K key)
        {
#pragma warning disable CS8604 // Possible null reference argument.
            return dict.GetValueOrDefault<K, V>(key, default);
#pragma warning restore CS8604 // Possible null reference argument.
        }
    }
}

#endif
