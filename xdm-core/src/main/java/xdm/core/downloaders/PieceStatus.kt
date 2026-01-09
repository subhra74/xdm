package xdm.core.downloaders

enum class PieceStatus {
    Ready,
    InProgress,
    Done,
    ConnectError,
    ServerError,
    NoResume,
    DiskError,
    Continue,
}
