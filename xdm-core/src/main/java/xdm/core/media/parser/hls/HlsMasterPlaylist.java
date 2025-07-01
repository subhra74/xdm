package xdm.core.media.parser.hls;

import java.net.URI;
import java.util.Map;
import lombok.Builder;
import lombok.Data;
import lombok.ToString;

@Data
@Builder
@ToString
public class HlsMasterPlaylist {
    private URI videoPlaylist;
    private URI audioPlaylist;
    private Map<String, String> attributes;
}
