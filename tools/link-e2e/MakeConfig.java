// Writes a test profile's config with the app's own AppConfig (the file is in the tagged format):
// downloads start without the new-download window, are saved to <out folder>, and show no completion dialog.
// Run with the app jar on the class path:  java -cp xdm-app.jar MakeConfig.java <data dir> <out folder>
public class MakeConfig {
    public static void main(String[] args) {
        xdm.app.AppConfig config = new xdm.app.AppConfig(args[0]);
        config.load();
        config.setStartDownloadAutomatically(true);
        config.setDefaultDownloadFolder(args[1]);
        config.setDownloadCompleteNotification(xdm.manager.DownloadCompleteNotification.NONE);
        config.setTheme("system");
        config.save();
        System.out.println("config written to " + args[0]);
    }
}
