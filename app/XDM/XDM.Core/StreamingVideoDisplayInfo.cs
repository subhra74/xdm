using System;
using System.Collections.Generic;
using System.Linq;
using System.Text;
using XDM.Core.Util;

namespace XDM.Core
{
    public struct StreamingVideoDisplayInfo
    {
        public string Quality { get; set; }
        public long Size { get; set; }
        public long Duration { get; set; }
        public DateTime CreationTime { get; set; }
        public string TabUrl { get; set; }

        public string DescriptionText
        {
            get
            {
                var sb = new StringBuilder();
                //if (Size > 0)
                //{
                //    sb.Append(FormattingHelper.FormatSize(Size));
                //}
                //AddSpece(sb);
                if (!string.IsNullOrEmpty(Quality))
                {
                    sb.Append(Quality);
                }
                AddSpece(sb);
                if (Duration > 0)
                {
                    sb.Append(FormattingHelper.ToHMS(Duration));
                }
                return sb.ToString();
            }
        }

        private void AddSpece(StringBuilder sb)
        {
            if (sb.Length > 0)
            {
                sb.Append(" ");
            }
        }
    }
}
