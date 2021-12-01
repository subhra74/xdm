//using System;
//using System.Collections;
//using System.Collections.Generic;
//using System.Linq;
//using System.Text;

//namespace NetFX.Polyfill
//{
//    public class ConcurrentDictionary<K, V> : IDictionary<K, V>
//    {
//        private Dictionary<K, V> dictionary;

//        public V this[K key] { get => throw new NotImplementedException(); set => throw new NotImplementedException(); }

//        public ICollection<K> Keys => throw new NotImplementedException();

//        public ICollection<V> Values => throw new NotImplementedException();

//        public int Count => throw new NotImplementedException();

//        public bool IsReadOnly => throw new NotImplementedException();

//        public void Add(K key, V value)
//        {
//            throw new NotImplementedException();
//        }

//        public void Add(KeyValuePair<K, V> item)
//        {
//            throw new NotImplementedException();
//        }

//        public void Clear()
//        {
//            throw new NotImplementedException();
//        }

//        public bool Contains(KeyValuePair<K, V> item)
//        {
//            throw new NotImplementedException();
//        }

//        public bool ContainsKey(K key)
//        {
//            throw new NotImplementedException();
//        }

//        public void CopyTo(KeyValuePair<K, V>[] array, int arrayIndex)
//        {
//            throw new NotImplementedException();
//        }

//        public IEnumerator<KeyValuePair<K, V>> GetEnumerator()
//        {
//            lock (this)
//            {
//                foreach (var item in dictionary)
//                {
//                    yield return item;
//                }
//            }
//        }

//        public bool Remove(K key)
//        {
//            lock (this)
//            {
//                return dictionary.Remove(key);
//            }
//        }

//        public bool Remove(KeyValuePair<K, V> item)
//        {
//            lock (this)
//            {
//                return dictionary.Remove(item.Key);
//            }
//        }

//        public bool TryGetValue(K key, out V value)
//        {
//            lock (this)
//            {
//                return dictionary.TryGetValue(key, out value);
//            }
//        }

//        IEnumerator IEnumerable.GetEnumerator()
//        {
//            lock (this)
//            {
//                foreach (var item in dictionary)
//                {
//                    yield return item;
//                }
//            }
//        }
//    }
//}
