package android.view;
import android.os.Looper;
public class InputEventReceiver {
    public InputEventReceiver(InputChannel channel, Looper looper) {}
    public void onInputEvent(InputEvent event) {}
    public final void finishInputEvent(InputEvent event, boolean handled) {}
    public void dispose() {}
}
