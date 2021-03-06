/*
 *  Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  See the NOTICE file distributed with
 *  this work for additional information regarding copyright ownership.
 *  The ASF licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
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

package org.apache.commons.compress.archivers.zip;

import org.apache.commons.compress.archivers.ArchiveStreamFactory;
import org.apache.commons.compress.utils.FileNameUtils;
import org.apache.commons.compress.utils.MultiReadOnlySeekableByteChannel;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * {@link MultiReadOnlySeekableByteChannel} that knows wat a splitted
 * ZIP archive should look like.
 *
 * q<p>If you want to read a splitted archive using {@link ZipFile} then create an instance of this class from the parts of the archive.
 *
 * @since 1.20
 */
public class ZipSplitReadOnlySeekableByteChannel extends MultiReadOnlySeekableByteChannel {
    private final int ZIP_SPLIT_SIGNATURE_LENGTH = 4;
    private final ByteBuffer zipSplitSignatureByteBuffer =
        ByteBuffer.allocate(ZIP_SPLIT_SIGNATURE_LENGTH);

    /**
     * Concatenates the given channels.
     *
     * <p>The channels should be add in ascending order, e.g. z01,
     * z02, ... z99, zip please note that the .zip file is the last
     * segment and should be added as the last one in the channels</p>
     *
     * @param channels the channels to concatenate
     * @throws NullPointerException if channels is null
     * @throws IOException if the first channel doesn't seem to hold
     * the beginning of a split archive
     */
    public ZipSplitReadOnlySeekableByteChannel(List<SeekableByteChannel> channels)
        throws IOException {
        super(channels);

        // the first split zip segment should begin with zip split signature
        assertSplitSignature(channels);
    }

    /**
     * Based on the zip specification:
     *
     * <p>
     * 8.5.3 Spanned/Split archives created using PKZIP for Windows
     * (V2.50 or greater), PKZIP Command Line (V2.50 or greater),
     * or PKZIP Explorer will include a special spanning
     * signature as the first 4 bytes of the first segment of
     * the archive.  This signature (0x08074b50) will be
     * followed immediately by the local header signature for
     * the first file in the archive.
     *
     * <p>
     * the first 4 bytes of the first zip split segment should be the zip split signature(0x08074B50)
     *
     * @param channels channels to be valided
     * @throws IOException
     */
    private void assertSplitSignature(final List<SeekableByteChannel> channels)
        throws IOException {
        SeekableByteChannel channel = channels.get(0);
        // the zip split file signature is at the beginning of the first split segment
        channel.position(0L);

        zipSplitSignatureByteBuffer.rewind();
        channel.read(zipSplitSignatureByteBuffer);
        final ZipLong signature = new ZipLong(zipSplitSignatureByteBuffer.array());
        if (!signature.equals(ZipLong.DD_SIG)) {
            channel.position(0L);
            throw new IOException("The first zip split segment does not begin with split zip file signature");
        }

        channel.position(0L);
    }

    /**
     * Concatenates the given channels.
     *
     * @param channels the channels to concatenate, note that the LAST CHANNEL of channels should be the LAST SEGMENT(.zip)
     *                 and theses channels should be added in correct order (e.g. .z01, .z02... .z99, .zip)
     * @return SeekableByteChannel that concatenates all provided channels
     * @throws NullPointerException if channels is null
     */
    public static SeekableByteChannel forOrderedSeekableByteChannels(SeekableByteChannel... channels) throws IOException {
        if (Objects.requireNonNull(channels, "channels must not be null").length == 1) {
            return channels[0];
        }
        return new ZipSplitReadOnlySeekableByteChannel(Arrays.asList(channels));
    }

    /**
     * Concatenates the given channels.
     *
     * @param lastSegmentChannel channel of the last segment of split zip segments, its extension should be .zip
     * @param channels           the channels to concatenate except for the last segment,
     *                           note theses channels should be added in correct order (e.g. .z01, .z02... .z99)
     * @return SeekableByteChannel that concatenates all provided channels
     * @throws NullPointerException if lastSegmentChannel or channels is null
     * @throws IOException if the first channel doesn't seem to hold
     * the beginning of a split archive
     */
    public static SeekableByteChannel forOrderedSeekableByteChannels(SeekableByteChannel lastSegmentChannel,
        Iterable<SeekableByteChannel> channels) throws IOException {
        if (channels == null || lastSegmentChannel == null) {
            throw new NullPointerException("channels must not be null");
        }

        List<SeekableByteChannel> channelsList = new ArrayList<>();
        for (SeekableByteChannel channel : channels) {
            channelsList.add(channel);
        }
        channelsList.add(lastSegmentChannel);

        SeekableByteChannel[] channelArray = new SeekableByteChannel[channelsList.size()];
        return forOrderedSeekableByteChannels(channelsList.toArray(channelArray));
    }

    /**
     * Concatenates zip split files from the last segment(the extension SHOULD be .zip)
     *
     * @param lastSegmentFile the last segment of zip split files, note that the extension SHOULD be .zip
     * @return SeekableByteChannel that concatenates all zip split files
     * @throws IllegalArgumentException if the lastSegmentFile's extension is NOT .zip
     * @throws IOException if the first channel doesn't seem to hold
     * the beginning of a split archive
     */
    public static SeekableByteChannel buildFromLastSplitSegment(File lastSegmentFile) throws IOException {
        String extension = FileNameUtils.getExtension(lastSegmentFile.getCanonicalPath());
        if (!extension.equalsIgnoreCase(ArchiveStreamFactory.ZIP)) {
            throw new IllegalArgumentException("The extension of last zip split segment should be .zip");
        }

        File parent = lastSegmentFile.getParentFile();
        String fileBaseName = FileNameUtils.getBaseName(lastSegmentFile.getCanonicalPath());
        ArrayList<File> splitZipSegments = new ArrayList<>();

        // zip split segments should be like z01,z02....z(n-1) based on the zip specification
        Pattern pattern = Pattern.compile(Pattern.quote(fileBaseName) + ".[zZ][0-9]+");
        for (File file : parent.listFiles()) {
            if (!pattern.matcher(file.getName()).matches()) {
                continue;
            }

            splitZipSegments.add(file);
        }

        Collections.sort(splitZipSegments, new ZipSplitSegmentComparator());
        return forFiles(lastSegmentFile, splitZipSegments);
    }

    /**
     * Concatenates the given files.
     *
     * @param files the files to concatenate, note that the LAST FILE of files should be the LAST SEGMENT(.zip)
     *              and theses files should be added in correct order (e.g. .z01, .z02... .z99, .zip)
     * @return SeekableByteChannel that concatenates all provided files
     * @throws NullPointerException if files is null
     * @throws IOException          if opening a channel for one of the files fails
     * @throws IOException if the first channel doesn't seem to hold
     * the beginning of a split archive
     */
    public static SeekableByteChannel forFiles(File... files) throws IOException {
        List<SeekableByteChannel> channels = new ArrayList<>();
        for (File f : Objects.requireNonNull(files, "files must not be null")) {
            channels.add(Files.newByteChannel(f.toPath(), StandardOpenOption.READ));
        }
        if (channels.size() == 1) {
            return channels.get(0);
        }
        return new ZipSplitReadOnlySeekableByteChannel(channels);
    }

    /**
     * Concatenates the given files.
     *
     * @param lastSegmentFile the last segment of split zip segments, its extension should be .zip
     * @param files           the files to concatenate except for the last segment,
     *                        note theses files should be added in correct order (e.g. .z01, .z02... .z99)
     * @return SeekableByteChannel that concatenates all provided files
     * @throws IOException if the first channel doesn't seem to hold
     * the beginning of a split archive
     * @throws NullPointerException if files or lastSegmentFile is null
     */
    public static SeekableByteChannel forFiles(File lastSegmentFile, Iterable<File> files) throws IOException {
        if (files == null || lastSegmentFile == null) {
            throw new NullPointerException("files must not be null");
        }

        List<File> filesList = new ArrayList<>();
        for (File f : files) {
            filesList.add(f);
        }
        filesList.add(lastSegmentFile);

        File[] filesArray = new File[filesList.size()];
        return forFiles(filesList.toArray(filesArray));
    }

    public static class ZipSplitSegmentComparator implements Comparator<File> {
        @Override
        public int compare(File file1, File file2) {
            String extension1 = FileNameUtils.getExtension(file1.getPath());
            String extension2 = FileNameUtils.getExtension(file2.getPath());

            if (!extension1.startsWith("z")) {
                return -1;
            }

            if (!extension2.startsWith("z")) {
                return 1;
            }

            Integer splitSegmentNumber1 = Integer.parseInt(extension1.substring(1));
            Integer splitSegmentNumber2 = Integer.parseInt(extension2.substring(1));

            return splitSegmentNumber1.compareTo(splitSegmentNumber2);
        }
    }
}
