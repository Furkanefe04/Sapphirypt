package com.sapphircrypt.sapphirypt.format;

import com.sapphircrypt.sapphirypt.storage.VaultCorruptedException;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * In-memory vault directory tree and block reference snapshot serialized into encrypted
 * {@code METADATA_SNAPSHOT} records.
 */
public final class VaultMetadata {
    public static final UUID ROOT_ID = new UUID(0L, 0L);
    public static final int MAX_FILE_VERSIONS = 10;
    public static final int MAX_TAGS_PER_ENTRY = 20;
    public static final int MAX_TAG_LENGTH = 32;
    private static final byte[] MAGIC = "SMET".getBytes(StandardCharsets.US_ASCII);
    private static final short SCHEMA_VERSION_V1 = 1;
    private static final short SCHEMA_VERSION = 2;

    public enum EntryType {
        FOLDER((byte) 0),
        FILE((byte) 1);

        private final byte code;

        EntryType(byte code) {
            this.code = code;
        }

        public byte code() {
            return code;
        }

        public static EntryType fromCode(byte code) throws VaultCorruptedException {
            return switch (code) {
                case 0 -> FOLDER;
                case 1 -> FILE;
                default -> throw new VaultCorruptedException("Bilinmeyen giriş türü kodu: " + code);
            };
        }
    }

    public record BlockRef(
            UUID blockId,
            int blockIndex,
            long recordOffset,
            int encryptedRecordLength,
            int plaintextLength,
            long nonceCounter,
            long recordSequence,
            byte[] plaintextSha256) {

        public BlockRef {
            Objects.requireNonNull(blockId, "blockId");
            if (plaintextSha256 == null || plaintextSha256.length != 32) {
                throw new IllegalArgumentException("plaintextSha256 must be 32 bytes");
            }
            plaintextSha256 = Arrays.copyOf(plaintextSha256, 32);
        }

        @Override
        public byte[] plaintextSha256() {
            return Arrays.copyOf(plaintextSha256, 32);
        }

        public BlockRef withOffsetAndRecordLength(long newOffset, int newRecordLength) {
            return new BlockRef(
                    blockId, blockIndex, newOffset, newRecordLength,
                    plaintextLength, nonceCounter, recordSequence, plaintextSha256);
        }
    }

    public record FileVersion(
            UUID versionId,
            long savedAt,
            long logicalSize,
            List<BlockRef> blocks) {

        public FileVersion {
            Objects.requireNonNull(versionId, "versionId");
            blocks = blocks == null ? List.of() : List.copyOf(blocks);
        }
    }

    public record VaultEntry(
            UUID id,
            UUID parentId,
            EntryType type,
            String name,
            long createdAt,
            long modifiedAt,
            long logicalSize,
            List<BlockRef> blocks,
            boolean favorite,
            List<String> tags,
            List<FileVersion> versions) {

        public VaultEntry {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(name, "name");
            blocks = blocks == null ? List.of() : List.copyOf(blocks);
            tags = normalizeTags(tags);
            versions = (type == EntryType.FOLDER || versions == null) ? List.of() : List.copyOf(versions);
        }

        public VaultEntry(
                UUID id,
                UUID parentId,
                EntryType type,
                String name,
                long createdAt,
                long modifiedAt,
                long logicalSize,
                List<BlockRef> blocks) {
            this(id, parentId, type, name, createdAt, modifiedAt, logicalSize, blocks, false, List.of(), List.of());
        }

        public VaultEntry withFavorite(boolean nextFavorite) {
            return new VaultEntry(id, parentId, type, name, createdAt, modifiedAt, logicalSize, blocks, nextFavorite, tags, versions);
        }

        public VaultEntry withTags(Collection<String> nextTags) {
            return new VaultEntry(id, parentId, type, name, createdAt, modifiedAt, logicalSize, blocks, favorite, normalizeTags(nextTags), versions);
        }

        public VaultEntry withVersions(List<FileVersion> nextVersions) {
            return new VaultEntry(id, parentId, type, name, createdAt, modifiedAt, logicalSize, blocks, favorite, tags, nextVersions);
        }

        public boolean isFolder() {
            return type == EntryType.FOLDER;
        }

        public boolean isFile() {
            return type == EntryType.FILE;
        }

        public String extensionLower() {
            if (isFolder()) {
                return "";
            }
            int idx = name.lastIndexOf('.');
            if (idx <= 0 || idx == name.length() - 1) {
                return "";
            }
            return name.substring(idx + 1).toLowerCase(Locale.ROOT);
        }
    }

    public static List<String> normalizeTags(Collection<String> rawTags) {
        if (rawTags == null || rawTags.isEmpty()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        Set<String> seenLower = new HashSet<>();
        for (String raw : rawTags) {
            if (raw == null) {
                continue;
            }
            String trimmed = raw.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (trimmed.length() > MAX_TAG_LENGTH) {
                trimmed = trimmed.substring(0, MAX_TAG_LENGTH).trim();
            }
            if (trimmed.isEmpty()) {
                continue;
            }
            String lower = trimmed.toLowerCase(Locale.ROOT);
            if (seenLower.add(lower)) {
                out.add(trimmed);
                if (out.size() >= MAX_TAGS_PER_ENTRY) {
                    break;
                }
            }
        }
        return out.isEmpty() ? List.of() : List.copyOf(out);
    }

    public record StorageStats(
            long logicalSize,
            long physicalFileSize,
            long activeEncryptedBytes,
            long orphanBytes,
            long estimatedReclaimableBytes,
            int fileCount,
            int folderCount,
            int uniqueBlockCount,
            int totalBlockRefCount) {
    }

    private long metadataSequence;
    private long nextRecordSequence;
    private final Map<UUID, VaultEntry> entries;

    private VaultMetadata(long metadataSequence, long nextRecordSequence, Map<UUID, VaultEntry> entries) {
        this.metadataSequence = metadataSequence;
        this.nextRecordSequence = nextRecordSequence;
        this.entries = entries;
    }

    public static VaultMetadata createEmpty() {
        Map<UUID, VaultEntry> map = new LinkedHashMap<>();
        long now = System.currentTimeMillis();
        map.put(ROOT_ID, new VaultEntry(ROOT_ID, null, EntryType.FOLDER, "Kasa", now, now, 0L, List.of()));
        return new VaultMetadata(1L, 1L, map);
    }

    public VaultMetadata copyMutable() {
        return new VaultMetadata(metadataSequence, nextRecordSequence, new LinkedHashMap<>(entries));
    }

    public long metadataSequence() {
        return metadataSequence;
    }

    public void setMetadataSequence(long metadataSequence) {
        this.metadataSequence = metadataSequence;
    }

    public long allocateRecordSequence() {
        return nextRecordSequence++;
    }

    public long nextRecordSequence() {
        return nextRecordSequence;
    }

    public void ensureNextRecordSequenceAtLeast(long minNext) {
        if (nextRecordSequence < minNext) {
            nextRecordSequence = minNext;
        }
    }

    public Optional<VaultEntry> getEntry(UUID id) {
        return Optional.ofNullable(entries.get(id));
    }

    public Collection<VaultEntry> allEntries() {
        return List.copyOf(entries.values());
    }

    public void putEntry(VaultEntry entry) {
        entries.put(entry.id(), entry);
    }

    public VaultEntry removeEntry(UUID id) {
        if (ROOT_ID.equals(id)) throw new IllegalArgumentException("Kök klasör silinemez");

        return entries.remove(id);
    }

    public List<VaultEntry> listChildren(UUID folderId) {
        List<VaultEntry> list = new ArrayList<>();
        for (VaultEntry entry : entries.values()) {
            if (folderId.equals(entry.parentId())) list.add(entry);
        }
        list.sort(Comparator.comparing((VaultEntry e) -> e.isFolder() ? 0 : 1).thenComparing(e -> e.name().toLowerCase(Locale.ROOT)));
        return list;
    }

    public Optional<VaultEntry> findChildByName(UUID folderId, String name) {
        String normalized = name.toLowerCase(Locale.ROOT);
        for (VaultEntry entry : entries.values()) {
            if (folderId.equals(entry.parentId()) && entry.name().toLowerCase(Locale.ROOT).equals(normalized)) {
                return Optional.of(entry);
            }
        }
        return Optional.empty();
    }

    public boolean isDescendantOrSelf(UUID candidateId, UUID ancestorId) {
        UUID cur = candidateId;
        int guard = 0;
        while (cur != null && guard++ < entries.size() + 2) {
            if (cur.equals(ancestorId)) return true;

            VaultEntry e = entries.get(cur);
            cur = e != null ? e.parentId() : null;
        }
        return false;
    }

    public String buildPath(UUID entryId) {
        List<String> parts = new ArrayList<>();
        UUID cur = entryId;
        int guard = 0;
        while (cur != null && !ROOT_ID.equals(cur) && guard++ < entries.size() + 2) {
            VaultEntry e = entries.get(cur);
            if (e == null) break;

            parts.add(e.name());
            cur = e.parentId();
        }
        if (parts.isEmpty()) return "/";
        StringBuilder sb = new StringBuilder();
        for (int i = parts.size() - 1; i >= 0; i--) {
            sb.append('/').append(parts.get(i));
        }
        return sb.toString();
    }

    public List<VaultEntry> search(UUID scopeFolderId, boolean recursive, String query, String extensionFilter) {
        return search(scopeFolderId, recursive, query, extensionFilter, false, null);
    }

    public List<VaultEntry> search(
            UUID scopeFolderId,
            boolean recursive,
            String query,
            String extensionFilter,
            boolean favoritesOnly,
            String tagFilter) {
        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        String ext = extensionFilter == null ? "" : extensionFilter.trim().toLowerCase(Locale.ROOT);
        if (ext.startsWith(".")) {
            ext = ext.substring(1);
        }
        String tag = tagFilter == null ? "" : tagFilter.trim().toLowerCase(Locale.ROOT);

        List<VaultEntry> candidates;
        if (!recursive) {
            candidates = listChildren(scopeFolderId);
        } else {
            candidates = new ArrayList<>();
            collectDescendants(scopeFolderId, candidates);
        }

        List<VaultEntry> result = new ArrayList<>();
        for (VaultEntry entry : candidates) {
            if (ROOT_ID.equals(entry.id())) continue;

            if (!q.isEmpty() && !entry.name().toLowerCase(Locale.ROOT).contains(q)) continue;

            if (!ext.isEmpty()) {
                if (entry.isFolder() || !entry.extensionLower().equals(ext)) continue;
            }
            if (favoritesOnly && !entry.favorite()) continue;

            if (!tag.isEmpty()) {
                boolean hasTag = false;
                for (String entryTag : entry.tags()) {
                    if (entryTag.toLowerCase(Locale.ROOT).equals(tag)) {
                        hasTag = true;
                        break;
                    }
                }
                if (!hasTag) continue;
            }
            result.add(entry);
        }
        result.sort(Comparator.comparing((VaultEntry e) -> e.isFolder() ? 0 : 1).thenComparing(e -> e.name().toLowerCase(Locale.ROOT)));
        return result;
    }

    private void collectDescendants(UUID folderId, List<VaultEntry> out) {
        for (VaultEntry child : listChildren(folderId)) {
            out.add(child);
            if (child.isFolder()) collectDescendants(child.id(), out);

        }
    }

    public List<UUID> collectSubtreeIds(UUID rootEntryId) {
        List<UUID> ids = new ArrayList<>();
        collectSubtreeIdsRecursive(rootEntryId, ids);
        return ids;
    }

    private void collectSubtreeIdsRecursive(UUID id, List<UUID> ids) {
        ids.add(id);
        VaultEntry entry = entries.get(id);
        if (entry != null && entry.isFolder()) {
            for (VaultEntry child : listChildren(id)) {
                collectSubtreeIdsRecursive(child.id(), ids);
            }
        }
    }

    public long computeFolderLogicalSize(UUID folderId) {
        long total = 0L;
        for (VaultEntry child : entries.values()) {
            if (folderId.equals(child.parentId())) {
                if (child.isFile()) {
                    total += child.logicalSize();
                } else {
                    total += computeFolderLogicalSize(child.id());
                }
            }
        }
        return total;
    }

    public Set<String> collectDistinctExtensions() {
        Set<String> exts = new HashSet<>();
        for (VaultEntry entry : entries.values()) {
            String ext = entry.extensionLower();
            if (!ext.isEmpty()) exts.add(ext);
        }
        return exts;
    }

    public Set<String> collectDistinctTags() {
        Map<String, String> byLower = new HashMap<>();
        for (VaultEntry entry : entries.values()) {
            if (ROOT_ID.equals(entry.id())) continue;
            for (String tag : entry.tags()) {
                byLower.putIfAbsent(tag.toLowerCase(Locale.ROOT), tag);
            }
        }
        List<String> sorted = new ArrayList<>(byLower.values());
        sorted.sort(String.CASE_INSENSITIVE_ORDER);
        return new java.util.LinkedHashSet<>(sorted);
    }

    public Map<UUID, BlockRef> collectUniqueActiveBlocks() {
        Map<UUID, BlockRef> unique = new LinkedHashMap<>();
        for (VaultEntry entry : entries.values()) {
            if (entry.isFile()) {
                for (BlockRef ref : entry.blocks()) {
                    unique.putIfAbsent(ref.blockId(), ref);
                }
                for (FileVersion version : entry.versions()) {
                    for (BlockRef ref : version.blocks()) {
                        unique.putIfAbsent(ref.blockId(), ref);
                    }
                }
            }
        }
        return unique;
    }

    public StorageStats computeStats(long physicalFileSize, int activeMetadataRecordLength) {
        long logical = 0L;
        int files = 0;
        int folders = 0;
        int totalRefs = 0;
        Map<UUID, BlockRef> uniqueBlocks = new HashMap<>();

        for (VaultEntry entry : entries.values()) {
            if (ROOT_ID.equals(entry.id())) {
                continue;
            }
            if (entry.isFolder()) {
                folders++;
            } else {
                files++;
                logical += entry.logicalSize();
                for (BlockRef ref : entry.blocks()) {
                    totalRefs++;
                    uniqueBlocks.putIfAbsent(ref.blockId(), ref);
                }
                for (FileVersion version : entry.versions()) {
                    for (BlockRef ref : version.blocks()) {
                        totalRefs++;
                        uniqueBlocks.putIfAbsent(ref.blockId(), ref);
                    }
                }
            }
        }

        long activeDataBytes = 0L;
        for (BlockRef ref : uniqueBlocks.values()) {
            activeDataBytes += ref.encryptedRecordLength();
        }
        long fixedAndCommit = VaultConstants.RECORDS_START_OFFSET + activeMetadataRecordLength + RecordFrame.COMMIT_TOTAL_RECORD_BYTES;
        long activeTotal = Math.min(physicalFileSize, fixedAndCommit + activeDataBytes);
        long orphan = Math.max(0L, physicalFileSize - activeTotal);

        return new StorageStats(logical, physicalFileSize, activeTotal, orphan, orphan, files, folders, uniqueBlocks.size(), totalRefs);
    }

    public byte[] serialize() {
        List<byte[]> encodedNames = new ArrayList<>(entries.size());
        List<List<byte[]>> encodedEntryTags = new ArrayList<>(entries.size());
        int totalBytes = 4 + 2 + 8 + 8 + 4; // magic + version + seq + nextRecSeq + count
        for (VaultEntry entry : entries.values()) {
            byte[] nameBytes = entry.name().getBytes(StandardCharsets.UTF_8);
            encodedNames.add(nameBytes);
            totalBytes += 16 + 16 + 1 + 4 + nameBytes.length + 8 + 8 + 8 + 4;
            totalBytes += entry.blocks().size() * 84;

            List<byte[]> tagBytesList = new ArrayList<>(entry.tags().size());
            totalBytes += 1 + 4; // favorite byte + tagCount
            for (String tag : entry.tags()) {
                byte[] tb = tag.getBytes(StandardCharsets.UTF_8);
                tagBytesList.add(tb);
                totalBytes += 4 + tb.length;
            }
            encodedEntryTags.add(tagBytesList);

            totalBytes += 4; // versionCount
            for (FileVersion version : entry.versions()) {
                totalBytes += 16 + 8 + 8 + 4; // versionId + savedAt + logicalSize + blockCount
                totalBytes += version.blocks().size() * 84;
            }
        }

        ByteBuffer buf = ByteBuffer.allocate(totalBytes).order(ByteOrder.BIG_ENDIAN);
        buf.put(MAGIC);
        buf.putShort(SCHEMA_VERSION);
        buf.putLong(metadataSequence);
        buf.putLong(nextRecordSequence);
        buf.putInt(entries.size());

        int i = 0;
        for (VaultEntry entry : entries.values()) {
            byte[] nameBytes = encodedNames.get(i);
            List<byte[]> tagBytesList = encodedEntryTags.get(i);
            i++;
            putUuid(buf, entry.id());
            putUuid(buf, entry.parentId());
            buf.put(entry.type().code());
            buf.putInt(nameBytes.length);
            buf.put(nameBytes);
            buf.putLong(entry.createdAt());
            buf.putLong(entry.modifiedAt());
            buf.putLong(entry.logicalSize());
            writeBlockRefs(buf, entry.blocks());

            buf.put((byte) (entry.favorite() ? 1 : 0));
            buf.putInt(tagBytesList.size());
            for (byte[] tb : tagBytesList) {
                buf.putInt(tb.length);
                buf.put(tb);
            }
            buf.putInt(entry.versions().size());
            for (FileVersion version : entry.versions()) {
                putUuid(buf, version.versionId());
                buf.putLong(version.savedAt());
                buf.putLong(version.logicalSize());
                writeBlockRefs(buf, version.blocks());
            }
        }
        return buf.array();
    }

    private static void writeBlockRefs(ByteBuffer buf, List<BlockRef> blocks) {
        buf.putInt(blocks.size());
        for (BlockRef ref : blocks) {
            putUuid(buf, ref.blockId());
            buf.putInt(ref.blockIndex());
            buf.putLong(ref.recordOffset());
            buf.putInt(ref.encryptedRecordLength());
            buf.putInt(ref.plaintextLength());
            buf.putLong(ref.nonceCounter());
            buf.putLong(ref.recordSequence());
            buf.put(ref.plaintextSha256());
        }
    }

    public static VaultMetadata deserialize(byte[] bytes) throws VaultCorruptedException {
        if (bytes == null || bytes.length < 26) {
            throw new VaultCorruptedException("Metaveri snapshot çok kısa");
        }
        byte[] magic = Arrays.copyOfRange(bytes, 0, 4);
        if (!Arrays.equals(magic, MAGIC)) {
            throw new VaultCorruptedException("Geçersiz metaveri başlığı");
        }
        ByteBuffer buf = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN);
        buf.position(4);
        short version = buf.getShort();
        if (version != SCHEMA_VERSION_V1 && version != SCHEMA_VERSION) {
            throw new VaultCorruptedException("Desteklenmeyen metaveri şema sürümü: " + version);
        }
        long metaSeq = buf.getLong();
        long nextRecSeq = buf.getLong();
        int count = buf.getInt();
        if (count < 1 || count > 2_000_000) {
            throw new VaultCorruptedException("Geçersiz metaveri giriş sayısı: " + count);
        }
        Map<UUID, VaultEntry> entries = new LinkedHashMap<>(count);
        for (int i = 0; i < count; i++) {
            if (buf.remaining() < 37) {
                throw new VaultCorruptedException("Metaveri beklenenden erken sona erdi");
            }
            UUID id = getUuid(buf);
            UUID parentId = getUuidOrNull(buf);
            EntryType type = EntryType.fromCode(buf.get());
            int nameLen = buf.getInt();
            if (nameLen < 0 || nameLen > 16384 || buf.remaining() < nameLen + 28) {
                throw new VaultCorruptedException("Geçersiz giriş adı uzunluğu: " + nameLen);
            }
            byte[] nameBytes = new byte[nameLen];
            buf.get(nameBytes);
            String name = new String(nameBytes, StandardCharsets.UTF_8);
            long created = buf.getLong();
            long modified = buf.getLong();
            long logicalSize = buf.getLong();
            List<BlockRef> blocks = readBlockRefs(buf);

            boolean favorite = false;
            List<String> tags = List.of();
            List<FileVersion> versions = List.of();

            if (version >= SCHEMA_VERSION) {
                if (buf.remaining() < 9) {
                    throw new VaultCorruptedException("Metaveri v2 alanları eksik");
                }
                favorite = buf.get() != 0;
                int tagCount = buf.getInt();
                if (tagCount < 0 || tagCount > 256) {
                    throw new VaultCorruptedException("Geçersiz etiket sayısı: " + tagCount);
                }
                if (tagCount > 0) {
                    List<String> parsedTags = new ArrayList<>(tagCount);
                    for (int t = 0; t < tagCount; t++) {
                        if (buf.remaining() < 4) {
                            throw new VaultCorruptedException("Metaveri etiket uzunluğu eksik");
                        }
                        int tLen = buf.getInt();
                        if (tLen < 0 || tLen > 512 || buf.remaining() < tLen) {
                            throw new VaultCorruptedException("Geçersiz etiket uzunluğu: " + tLen);
                        }
                        byte[] tBytes = new byte[tLen];
                        buf.get(tBytes);
                        parsedTags.add(new String(tBytes, StandardCharsets.UTF_8));
                    }
                    tags = parsedTags;
                }
                if (buf.remaining() < 4) {
                    throw new VaultCorruptedException("Metaveri sürüm sayısı eksik");
                }
                int versionCount = buf.getInt();
                if (versionCount < 0 || versionCount > 1024) {
                    throw new VaultCorruptedException("Geçersiz sürüm sayısı: " + versionCount);
                }
                if (versionCount > 0) {
                    List<FileVersion> parsedVersions = new ArrayList<>(versionCount);
                    for (int v = 0; v < versionCount; v++) {
                        if (buf.remaining() < 36) {
                            throw new VaultCorruptedException("Metaveri sürüm kaydı eksik");
                        }
                        UUID versionId = getUuid(buf);
                        long savedAt = buf.getLong();
                        long verSize = buf.getLong();
                        List<BlockRef> verBlocks = readBlockRefs(buf);
                        parsedVersions.add(new FileVersion(versionId, savedAt, verSize, verBlocks));
                    }
                    versions = parsedVersions;
                }
            }

            entries.put(id, new VaultEntry(id, parentId, type, name, created, modified, logicalSize, blocks, favorite, tags, versions));
        }
        if (!entries.containsKey(ROOT_ID)) {
            throw new VaultCorruptedException("Kasa metaverisinde kök klasör eksik");
        }
        return new VaultMetadata(metaSeq, nextRecSeq, entries);
    }

    private static List<BlockRef> readBlockRefs(ByteBuffer buf) throws VaultCorruptedException {
        if (buf.remaining() < 4) {
            throw new VaultCorruptedException("Blok referans sayısı okunamadı");
        }
        int blockCount = buf.getInt();
        if (blockCount < 0 || blockCount > 10_000_000 || (long) blockCount * 84L > buf.remaining()) {
            throw new VaultCorruptedException("Geçersiz blok referans sayısı: " + blockCount);
        }
        List<BlockRef> blocks = new ArrayList<>(blockCount);
        for (int b = 0; b < blockCount; b++) {
            UUID blockId = getUuid(buf);
            int blockIdx = buf.getInt();
            long recOffset = buf.getLong();
            int encLen = buf.getInt();
            int ptLen = buf.getInt();
            long nonceCtr = buf.getLong();
            long recSeq = buf.getLong();
            byte[] sha = new byte[32];
            buf.get(sha);
            blocks.add(new BlockRef(blockId, blockIdx, recOffset, encLen, ptLen, nonceCtr, recSeq, sha));
        }
        return blocks;
    }

    private static void putUuid(ByteBuffer buf, UUID id) {
        if (id == null) {
            buf.putLong(-1L);
            buf.putLong(-1L);
        } else {
            buf.putLong(id.getMostSignificantBits());
            buf.putLong(id.getLeastSignificantBits());
        }
    }

    private static UUID getUuid(ByteBuffer buf) {
        return new UUID(buf.getLong(), buf.getLong());
    }

    private static UUID getUuidOrNull(ByteBuffer buf) {
        long msb = buf.getLong();
        long lsb = buf.getLong();
        if (msb == -1L && lsb == -1L) {
            return null;
        }
        return new UUID(msb, lsb);
    }
}
