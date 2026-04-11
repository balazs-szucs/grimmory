import org.springframework.aot.hint.RuntimeHints;
import java.lang.reflect.Method;

public class CheckApi {
    public static void main(String[] args) {
        for (Method m : RuntimeHints.class.getMethods()) {
            System.out.println("RuntimeHints method: " + m.getName());
            if (m.getName().equals("jni")) {
                for (Method jniM : m.getReturnType().getMethods()) {
                    System.out.println("  jni method: " + jniM.getName());
                }
            }
        }
    }
}
