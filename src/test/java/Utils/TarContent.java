package Utils;

import java.io.IOException;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;

@FunctionalInterface
public interface TarContent {
    void write(String root, TarArchiveOutputStream tar) throws IOException;
}
