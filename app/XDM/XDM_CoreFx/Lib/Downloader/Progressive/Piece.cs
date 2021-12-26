using System.Threading;


namespace XDM.Core.Lib.Downloader.Progressive
{
    public class Piece
    {
        private long offset, length, downloaded;
        private SegmentState state;
        public long Offset
        {
            get => Interlocked.Read(ref offset);
            set => Interlocked.Exchange(ref offset, value);
        }
        public long Length
        {
            get => Interlocked.Read(ref length);
            set => Interlocked.Exchange(ref length, value);
        }
        public long Downloaded
        {
            get => Interlocked.Read(ref downloaded);
            set => Interlocked.Exchange(ref downloaded, value);
        }
        public SegmentState State
        {
            get { lock (this) { return this.state; } }
            set { lock (this) { this.state = value; } }
        }
        public string Id { get; set; }
        public StreamType StreamType { get; set; }
    }

    public enum StreamType
    {
        Primary, Secondary
    }
}
