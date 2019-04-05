package xdman.downloaders;
import java.util.Comparator;

public class SegmentComparator implements Comparator<Segment> {

	@Override
	public int compare(Segment c1, Segment c2) {
		if (c1.getStartOffset() > c2.getStartOffset()) {
			return 1;
		} else if (c1.getStartOffset() < c2.getStartOffset()) {
			return -1;
		} else {
			return 0;
		}
	}
}
