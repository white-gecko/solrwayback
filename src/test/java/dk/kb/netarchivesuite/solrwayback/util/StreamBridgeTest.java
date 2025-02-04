package dk.kb.netarchivesuite.solrwayback.util;

import dk.kb.netarchivesuite.solrwayback.UnitTestUtils;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.apache.commons.io.IOUtils;
import org.junit.Assert;
import org.junit.Test;

import java.io.File;
import java.io.FileInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.Consumer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

/*
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
public class StreamBridgeTest extends UnitTestUtils {

    @Test
    public void testOutputToInputStream() throws IOException {
        int result;
        try (InputStream in = StreamBridge.outputToInputSafe(
                (out) -> out.write(87))) {
            result = in.read();
        }
        assertEquals(87, result);
    }

    @Test
    public void testOutputToInputStreamFail() throws IOException {
        try (InputStream in = StreamBridge.outputToInputSafe((out) -> {
            throw new RuntimeException("Failing on purpose");
        })) {
            try {
                int result = in.read();
                fail("An exception should have been thrown");
            } catch (Exception e) {
                // Expected
            }
        }
        ;
    }

    @Test
    public void testGuaranteedStreamMemory() throws IOException {
        File testFile = getFile("compressions_warc/transfer_compression_brotli.warc");
        long filesize = Files.size(Paths.get(testFile.getPath()));
        try (StatusInputStream guaranteed = StreamBridge.guaranteedStream(
                new FileInputStream(testFile), (int) filesize)) {
            assertEquals("The guaranteed resolved size should match the file size", filesize, guaranteed.size());

            long delivered = IOUtils.skip(guaranteed, filesize*2);
            assertEquals("The guaranteed delivered size should match the file size", filesize, delivered);
        }
    }

    // Light hammering of StreamBridge with gzipped content
    @Test
    public void testMonkeyGZipStream() throws IOException {
        final int DUPLICATES = 10;
        final int RUNS = 5;

        File testFile = getFile("compressions_warc/transfer_compression_brotli.warc");
        long filesize = Files.size(Paths.get(testFile.getPath()));

        for (int run = 0 ; run < RUNS ; run++) {
            List<Consumer<OutputStream>> providers = new ArrayList<>(3);
            Consumer<OutputStream> zippedWARC = StreamBridge.gzip(out -> {
                try (FileInputStream fis = new FileInputStream(testFile)) {
                    IOUtils.copyLarge(fis, out);
                } catch (IOException e) {
                    throw new RuntimeException("IOException copying content", e);
                }
            });
            for (int i = 0; i < DUPLICATES; i++) {
                providers.add(zippedWARC);
            }

            Random r = new Random();
            try (InputStream allIn = StreamBridge.outputToInput(providers);
                 GzipCompressorInputStream gis = new GzipCompressorInputStream(allIn, true)) {
                try {
                    Thread.sleep(r.nextInt(100));
                } catch (InterruptedException e) {
                    // Do nothing
                }
                long delivered = IOUtils.skip(gis, filesize * (DUPLICATES + 1)); // +1 to be sure we read enough
                assertEquals("The delivered size should match the file size " + filesize +
                        " times DUPLICATES " + DUPLICATES + " for run " + run,
                        filesize * DUPLICATES, delivered);
            }
        }
    }

    @Test
    public void testGuaranteedStreamStorage() throws IOException {
        File testFile = getFile("compressions_warc/transfer_compression_brotli.warc");
        long filesize = Files.size(Paths.get(testFile.getPath()));
        try (StatusInputStream guaranteed = StreamBridge.guaranteedStream(
                new FileInputStream(testFile), (int) filesize/2)) {
            assertEquals("The guaranteed resolved size should match the file size", filesize, guaranteed.size());

            long delivered = IOUtils.skip(guaranteed, filesize*2);
            assertEquals("The guaranteed delivered size should match the file size", filesize, delivered);
        }
    }

    @Test
    public void testGuaranteedStreamFail() throws IOException {
        File testFile = getFile("compressions_warc/transfer_compression_brotli.warc");
        long filesize = Files.size(Paths.get(testFile.getPath()));
        try (StatusInputStream guaranteed = StreamBridge.guaranteedStream(
                new FailingStream(new FileInputStream(testFile), filesize / 2), (int) filesize)) {
            assertEquals("The status of the guaranteed stream should be correct",
                                StatusInputStream.STATUS.exception, guaranteed.getStatus());
            Assert.assertNotSame("The guaranteed resolved size should not match the file size",
                                 filesize, guaranteed.size());

            long delivered = IOUtils.skip(guaranteed, filesize*2);
            Assert.assertNotSame("The guaranteed delivered size not should match the file size",
                                filesize, delivered);
        }
    }

    private static class FailingStream extends FilterInputStream {
        private final long maxBytes;
        private long readBytes = 0;

        public FailingStream(InputStream in, long maxBytes) {
            super(in);
            this.maxBytes = maxBytes;
        }

        @Override
        public int read() throws IOException {
            maybeFail();
            return super.read();
        }

        private void maybeFail() throws IOException {
            if (readBytes >= maxBytes) {
                throw new IOException("Limit of " + maxBytes + " bytes exceeded");
            }
        }

        @Override
        public int read(byte[] b) throws IOException {
            int r = super.read(b);
            if (r != -1) {
                readBytes += r;
                maybeFail();
            }
            return r;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int r = super.read(b, off, len);
            if (r != -1) {
                readBytes += r;
                maybeFail();
            }
            return r;
        }

        @Override
        public long skip(long n) throws IOException {
            long s = super.skip(n);
            readBytes += s;
            maybeFail();
            return s;
        }
    }
}