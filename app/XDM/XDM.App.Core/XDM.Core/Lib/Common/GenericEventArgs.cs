using System;
using System.Collections.Generic;
using System.Linq;
using System.Text;


namespace XDM.Core
{
    public class GenericEventArgs<T>
    {
        private T value;

        public GenericEventArgs(T value)
        {
            this.value = value;
        }

        public T Value => this.value;
    }

    public class GenericEventArgs<T, K>
    {
        private T value1;
        private K value2;

        public GenericEventArgs(T value1, K value2)
        {
            this.value1 = value1;
            this.value2 = value2;
        }

        public T Value1 => this.value1;
        public K Value2 => this.value2;
    }

    public class GenericEventArgs<T, K, V>
    {
        private T value1;
        private K value2;
        private V value3;

        public GenericEventArgs(T value1, K value2, V value3)
        {
            this.value1 = value1;
            this.value2 = value2;
            this.value3 = value3;
        }

        public T Value1 => this.value1;
        public K Value2 => this.value2;
        public V Value3 => this.value3;
    }

    public class GenericEventArgs<T, K, V, S>
    {
        private T value1;
        private K value2;
        private V value3;
        private S value4;

        public GenericEventArgs(T value1, K value2, V value3, S value4)
        {
            this.value1 = value1;
            this.value2 = value2;
            this.value3 = value3;
            this.value4 = value4;
        }

        public T Value1 => this.value1;
        public K Value2 => this.value2;
        public V Value3 => this.value3;
        public S Value4 => this.value4;
    }
}
