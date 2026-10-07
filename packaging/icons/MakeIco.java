import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;

/**
 * Regenerates packaging/icons/xdm.ico, the Windows launcher, shortcut and installer icon, from the
 * app's own logo (xdm-app/src/main/resources/images/xdm-logo-*.png).
 *
 * It uses the same images as XDM's Windows window and tray icons ({@code windowsLogoImage} in
 * UiHelper.kt): the logo cropped to its artwork, so it fills the canvas the way Windows icons do,
 * rather than keeping the macOS-style padding of the PNGs. One entry per size Windows asks for:
 * 32-bit BMP up to 64 px, PNG at 256 px.
 *
 * Run from the repository root after building the app jar (JDK 25, no compile step):
 *
 *   mvn -q -pl xdm-app -am package -DskipTests
 *   java -cp xdm-app/target/xdm-app.jar packaging/icons/MakeIco.java packaging/icons/xdm.ico
 */
public class MakeIco {

    private static final int[] SIZES = {16, 20, 24, 32, 40, 48, 64, 256};

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            System.err.println("usage: java -cp xdm-app/target/xdm-app.jar packaging/icons/MakeIco.java <out.ico>");
            System.exit(2);
        }
        System.setProperty("java.awt.headless", "true");

        List<byte[]> entries = new ArrayList<>();
        for (int size : SIZES) {
            BufferedImage image = xdm.app.utils.UiHelperKt.windowsLogoImage(size);
            if (image.getWidth() != size || image.getHeight() != size) {
                throw new IllegalStateException("Expected " + size + " px, got " + image.getWidth() + "x" + image.getHeight());
            }
            entries.add(size >= 256 ? png(image) : bmp(image));
        }

        // ICONDIR, then one ICONDIRENTRY per image (0 means 256 for width/height), then the images.
        ByteBuffer header = ByteBuffer.allocate(6 + 16 * SIZES.length).order(ByteOrder.LITTLE_ENDIAN);
        header.putShort((short) 0).putShort((short) 1).putShort((short) SIZES.length);
        int offset = header.capacity();
        for (int i = 0; i < SIZES.length; i++) {
            byte side = (byte) (SIZES[i] >= 256 ? 0 : SIZES[i]);
            header.put(side).put(side).put((byte) 0).put((byte) 0)
                    .putShort((short) 1).putShort((short) 32)
                    .putInt(entries.get(i).length).putInt(offset);
            offset += entries.get(i).length;
        }

        try (FileOutputStream out = new FileOutputStream(args[0])) {
            out.write(header.array());
            for (byte[] entry : entries) out.write(entry);
        }
        System.out.println("Wrote " + args[0] + " (" + offset + " bytes, sizes " + java.util.Arrays.toString(SIZES) + ")");
    }

    private static byte[] png(BufferedImage image) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(image, "png", bytes);
        return bytes.toByteArray();
    }

    /**
     * BITMAPINFOHEADER (height doubled, as ICO requires), bottom-up BGRA pixels, then an all-zero AND
     * mask: the alpha channel carries the transparency.
     */
    private static byte[] bmp(BufferedImage image) {
        int w = image.getWidth();
        int h = image.getHeight();
        int maskRow = ((w + 31) / 32) * 4;
        int pixels = w * h * 4;
        ByteBuffer b = ByteBuffer.allocate(40 + pixels + maskRow * h).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(40).putInt(w).putInt(h * 2).putShort((short) 1).putShort((short) 32)
                .putInt(0).putInt(pixels + maskRow * h).putInt(0).putInt(0).putInt(0).putInt(0);
        for (int y = h - 1; y >= 0; y--) {
            for (int x = 0; x < w; x++) {
                int argb = image.getRGB(x, y);
                b.put((byte) argb).put((byte) (argb >> 8)).put((byte) (argb >> 16)).put((byte) (argb >>> 24));
            }
        }
        return b.array();
    }
}
