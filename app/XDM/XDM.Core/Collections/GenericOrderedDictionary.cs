using System;
using System.Collections;
using System.Collections.Generic;
using System.Diagnostics.CodeAnalysis;
using System.Linq;
using System.Text;

namespace XDM.Core.Collections
{
    public class GenericOrderedDictionary<K, V> : IDictionary<K, V>
    {
        private readonly List<K> keys = new();
        private readonly Dictionary<K, V> dict = new();

        public V this[K key]
        {
            get => dict[key];
            set
            {
                lock (this)
                {
                    if (!dict.ContainsKey(key))
                    {
                        keys.Add(key);
                    }
                    dict[key] = value;
                }
            }
        }

        public ICollection<K> Keys => keys;

        public ICollection<V> Values
        {
            get
            {
                lock (this)
                {
                    return keys.Select(key => dict[key]).ToList();
                }
            }
        }

        public int Count => keys.Count;

        public bool IsReadOnly => false;

        public void Add(K key, V value)
        {
            lock (this)
            {
                if (!dict.ContainsKey(key))
                {
                    keys.Add(key);
                }
                dict[key] = value;
            }
        }

        public void Add(KeyValuePair<K, V> item)
        {
            Add(item.Key, item.Value);
        }

        public void Clear()
        {
            lock (this)
            {
                keys.Clear();
                dict.Clear();
            }
        }

        public bool Contains(KeyValuePair<K, V> item)
        {
            return ContainsKey(item.Key);
        }

        public bool ContainsKey(K key)
        {
            return dict.ContainsKey(key);
        }

        public void CopyTo(KeyValuePair<K, V>[] array, int arrayIndex)
        {
            lock (this)
            {
                foreach (var key in this.keys)
                {
                    var kv = new KeyValuePair<K, V>(key, this.dict[key]);
                    array[arrayIndex++] = kv;
                }
            }
        }

        public IEnumerator<KeyValuePair<K, V>> GetEnumerator()
        {
            foreach (var key in this.keys)
            {
                yield return new KeyValuePair<K, V>(key, this.dict[key]);
            }
        }

        public bool Remove(K key)
        {
            lock (this)
            {
                keys.Remove(key);
                return dict.Remove(key);
            }
        }

        public bool Remove(KeyValuePair<K, V> item)
        {
            return Remove(item.Key);
        }

        public bool TryGetValue(K key, out V value)
        {
            return this.dict.TryGetValue(key, out value);
        }

        IEnumerator IEnumerable.GetEnumerator()
        {
            foreach (var key in this.keys)
            {
                yield return new KeyValuePair<K, V>(key, this.dict[key]);
            }
        }
    }
}
