package spb;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static spb.Impl.shouldIgnoreFile;

public class ImplTest {

    @Test
    void testDefaultPattern() {
        assertThat(shouldIgnoreFile(".DS_Store")).isTrue();
        assertThat(shouldIgnoreFile("folder/.DS_Store")).isTrue();
        assertThat(shouldIgnoreFile("/folder1/folder2/folder/.DS_Store")).isTrue();

        assertThat(shouldIgnoreFile(".DS_store")).isFalse();
        assertThat(shouldIgnoreFile("DS_Store")).isFalse();
        assertThat(shouldIgnoreFile("SomethingElse.DS_Store")).isFalse();
    }

}
