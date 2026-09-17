package easyshare.messenger;

/** Entry point of the EasyShare Messenger desktop app. */
public final class MessengerApp {
    private MessengerApp() {
    }

    public static void main(String[] args) throws Exception {
        MessengerGui.launch(args);
    }
}
