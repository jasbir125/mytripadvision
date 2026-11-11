import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import java.nio.file.Path;

/**
 * Run once while the image is built. Loading a tokenizer makes DJL download its native library into
 * DJL_CACHE_DIR, so a new container does not have to do it at start.
 */
public class PrewarmTokenizer {

    public static void main(String[] args) throws Exception {
        try (HuggingFaceTokenizer tokenizer = HuggingFaceTokenizer.newInstance(Path.of(args[0]))) {
            System.out.println("tokenizer ready, test sentence has " + tokenizer.encode("warm up").getIds().length + " tokens");
        }
    }
}
