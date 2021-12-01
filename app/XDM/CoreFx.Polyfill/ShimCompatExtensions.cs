using System;
using System.Collections.Generic;
using System.Linq;
using System.Text;
using System.Text.RegularExpressions;
using System.Threading.Tasks;

namespace System
{
    public static class ShimCompatExtensions
    {
        
        public static string Join<T>(char separator, IEnumerable<T> values)
        {
            return string.Join(separator.ToString(), values);
        }

        public static bool ContainsKey<TKey, TValue>(this IDictionary<TKey, TValue> dict, TKey key)
        {
            return dict.TryGetValue(key, out _);
        }

        public static bool ContainsKey(this GroupCollection dict, string key)
        {
            try
            {
                return dict[key] != null;
            }
            catch
            {
                return false;
            }
        }

        public static TValue? GetValueOrDefault<TKey, TValue>(this IDictionary<TKey, TValue> dict, TKey key)
        {
            return GetValueOrDefault(dict, key, default(TValue)!);
        }

        /// <summary>
        /// Gets the value for a given key if a matching key exists in the dictionary.
        /// </summary>
        /// <param name="key">The key to search for.</param>
        /// <param name="defaultValue">The default value to return if no matching key is found in the dictionary.</param>
        /// <returns>
        /// The value for the key, or <paramref name="defaultValue"/> if no matching key was found.
        /// </returns>
        public static TValue GetValueOrDefault<TKey, TValue>(this IDictionary<TKey, TValue> dict, TKey key, TValue defaultValue)
        {

            TValue value;
            if (dict.TryGetValue(key, out value!))
            {
                return value;
            }

            return defaultValue;
        }
    }
}
