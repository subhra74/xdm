using System;
using System.Collections.Generic;
using System.Linq;
using System.Text;
using System.Threading.Tasks;

namespace CoreFx.Polyfill
{
    public static class StringExtensions
    {
        public static string[] Split(this string str)
        {
            return str.Split(str.ToCharArray());
        }
    }
}
