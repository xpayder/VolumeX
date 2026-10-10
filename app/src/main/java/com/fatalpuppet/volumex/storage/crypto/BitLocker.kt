package com.fatalpuppet.volumex.storage.crypto

import com.fatalpuppet.volumex.storage.disk.BlockDeviceReader
import java.security.MessageDigest
import java.util.zip.CRC32
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * BitLocker / BitLocker To Go (Windows 7 and later): metadata parsing, key derivation from a password or the 48-digit
 * recovery key, and the sector cipher. Layout and algorithms follow cryptsetup's `bitlk` and dislocker, and the
 * volumes produced by tools/make-bitlocker.py are opened by both of them.
 *
 * Supported ciphers: AES-XTS 128/256 (Windows 10+) and AES-CBC 128/256 without the Elephant diffuser.
 * Not supported: the diffuser modes of Windows 7 (0x8000/0x8001), TPM / smart-card / startup-key-only protectors.
 */
object BitLocker {
    private const val SIGNATURE = "-FVE-FS-"
    private const val TOGO_SIGNATURE = "MSWIN4.1"
    private val GUID_NORMAL = byteArrayOf(0x3b, 0xd6.toByte(), 0x67, 0x49, 0x29, 0x2e, 0xd8.toByte(), 0x4a, 0x83.toByte(), 0x99.toByte(), 0xf6.toByte(), 0xa3.toByte(), 0x39, 0xe3.toByte(), 0xd0.toByte(), 0x01)
    private const val META_SIZE = 64 * 1024

    const val PROTECTION_CLEAR = 0x0000
    const val PROTECTION_RECOVERY = 0x0800
    const val PROTECTION_PASSWORD = 0x2000

    class Vmk(val protection: Int, val salt: ByteArray?, val nonce: ByteArray?, val mac: ByteArray?, val enc: ByteArray?, val clearKey: ByteArray?)

    class Info(
        val volumeSize: Long, val method: Int, val description: String?, val vmks: List<Vmk>,
        val fvekNonce: ByteArray, val fvekMac: ByteArray, val fvekEnc: ByteArray,
        val validationNonce: ByteArray?, val validationMac: ByteArray?, val validationEnc: ByteArray?, val metadataSha256: ByteArray,
        val headerOffset: Long, val headerSize: Long, val normalState: Boolean
    ) {
        val keyLength: Int get() = when (method) { 0x8002 -> 16; 0x8003 -> 32; 0x8004 -> 32; 0x8005 -> 64; else -> 0 }
        /** Elephant-diffuser modes (Windows 7 default) are not implemented. */
        val supported: Boolean get() = method in 0x8002..0x8005 && normalState
    }

    private fun u16(b: ByteArray, o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
    private fun u32(b: ByteArray, o: Int) = (u16(b, o).toLong() or (u16(b, o + 2).toLong() shl 16))
    private fun u64(b: ByteArray, o: Int) = u32(b, o) or (u32(b, o + 4) shl 32)

    /** Parses the volume at [startLba]; null if it is not a BitLocker volume. */
    fun load(dev: BlockDeviceReader, startLba: Long): Info? {
        if (dev.sectorSize() != 512) return null
        val boot = dev.readSector(startLba) ?: return null
        val sig = String(boot, 3, 8, Charsets.ISO_8859_1)
        val offAt = when (sig) { SIGNATURE -> 160; TOGO_SIGNATURE -> 424; else -> return null }
        if (boot[0] != 0xEB.toByte() || boot[1] != 0x58.toByte() || boot[2] != 0x90.toByte()) return null
        if (!boot.copyOfRange(offAt, offAt + 16).contentEquals(GUID_NORMAL)) return null
        for (i in 0 until 3) {
            val off = u64(boot, offAt + 16 + 8 * i)
            if (off <= 0 || off % 512 != 0L || off > (1L shl 50)) continue
            parseBlock(dev, startLba, off)?.let { return it }
        }
        return null
    }

    private fun readBytes(dev: BlockDeviceReader, startLba: Long, byteOff: Long, len: Int): ByteArray? {
        val sectors = (len + 511) / 512
        return dev.readSectors(startLba + byteOff / 512, sectors)?.copyOf(len)
    }

    private fun parseBlock(dev: BlockDeviceReader, startLba: Long, off: Long): Info? {
        val head = readBytes(dev, startLba, off, 112) ?: return null
        if (String(head, 0, 8, Charsets.ISO_8859_1) != SIGNATURE || u16(head, 10) != 2) return null
        val real = u16(head, 8) shl 4
        if (real < 112 || real > META_SIZE) return null
        val block = readBytes(dev, startLba, off, real + 96) ?: return null
        val validation = block.copyOfRange(real, real + 96)
        val crc = CRC32().also { it.update(block, 0, real) }.value
        if (u16(validation, 0) < 8 || u16(validation, 2) > 2 || crc != u32(validation, 4)) return null

        val volumeSize = u64(head, 16)
        val curState = u16(head, 12); val nextState = u16(head, 14)
        val method = u16(block, 64 + 36)
        val metaSize = u32(block, 64).toInt()
        if (metaSize < 48 + 4 || metaSize > META_SIZE) return null
        val end = minOf(64 + metaSize, real)
        var p = 112
        var description: String? = null
        var hdrOff = 0L; var hdrSize = 0L
        val vmks = ArrayList<Vmk>()
        var fvek: Triple<ByteArray, ByteArray, ByteArray>? = null
        while (p + 8 <= end) {
            val size = u16(block, p)
            if (size == 0 || p + size > end) break
            val type = u16(block, p + 2)
            when (type) {
                2 -> if (size >= 8 + 28) vmks.add(parseVmk(block, p, p + size))
                3 -> if (fvek == null && size >= 8 + 12 + 16) fvek = Triple(
                    block.copyOfRange(p + 8, p + 20), block.copyOfRange(p + 20, p + 36), block.copyOfRange(p + 36, p + size)
                )
                0xF -> if (size >= 8 + 16) { hdrOff = u64(block, p + 8); hdrSize = u64(block, p + 16) }
                7 -> if (description == null && size >= 8) description = String(block, p + 8, size - 8, Charsets.UTF_16LE).trimEnd('\u0000')
            }
            p += size
        }
        if (fvek == null || hdrSize <= 0 || hdrOff <= 0) return null
        var vN: ByteArray? = null; var vM: ByteArray? = null; var vE: ByteArray? = null
        if (u16(validation, 12) == 80 && u16(validation, 16) == 5) {       // nested AES-CCM datum with the metadata hash
            vN = validation.copyOfRange(20, 32); vM = validation.copyOfRange(32, 48); vE = validation.copyOfRange(48, 92)
        }
        return Info(
            volumeSize, method, description, vmks, fvek.first, fvek.second, fvek.third, vN, vM, vE,
            MessageDigest.getInstance("SHA-256").digest(block.copyOf(real)), hdrOff, hdrSize, curState == 4 && nextState == 4
        )
    }

    private fun parseVmk(b: ByteArray, start: Int, end: Int): Vmk {
        val protection = u16(b, start + 8 + 26)
        var salt: ByteArray? = null; var nonce: ByteArray? = null; var mac: ByteArray? = null; var enc: ByteArray? = null; var clear: ByteArray? = null
        var p = start + 8 + 28
        while (p + 8 <= end) {
            val size = u16(b, p); if (size == 0 || p + size > end) break
            when (u16(b, p + 4)) {
                3 -> if (size >= 8 + 4 + 16) salt = b.copyOfRange(p + 12, p + 28)
                5 -> if (size >= 8 + 12 + 16) { nonce = b.copyOfRange(p + 8, p + 20); mac = b.copyOfRange(p + 20, p + 36); enc = b.copyOfRange(p + 36, p + size) }
                1 -> if (size > 12) clear = b.copyOfRange(p + 12, p + size)
            }
            p += size
        }
        return Vmk(protection, salt, nonce, mac, enc, clear)
    }

    // ---- key derivation ----
    /** BitLocker's key stretching: 2^20 rounds of SHA-256 over (last hash, initial hash, salt, counter). */
    private fun stretch(initial: ByteArray, salt: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(88)
        System.arraycopy(initial, 0, buf, 32, 32); System.arraycopy(salt, 0, buf, 64, 16)
        for (i in 0 until 0x100000) {
            val h = md.digest(buf)
            System.arraycopy(h, 0, buf, 0, 32)
            var c = i + 1                                        // counter lives in buf[80..88), little-endian
            buf[80] = c.toByte(); buf[81] = (c ushr 8).toByte(); buf[82] = (c ushr 16).toByte(); buf[83] = (c ushr 24).toByte()
        }
        return buf.copyOf(32)
    }

    private fun passwordInitial(pw: String): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(md.digest(pw.toByteArray(Charsets.UTF_16LE)))
    }

    /** 48-digit recovery password (8 groups of 6 digits, each divisible by 11) -> initial hash, or null if it is not one. */
    private fun recoveryInitial(text: String): ByteArray? {
        val groups = text.trim().replace(" ", "-").split('-').filter { it.isNotEmpty() }
        if (groups.size != 8 || groups.any { it.length != 6 || !it.all { c -> c.isDigit() } }) return null
        val raw = ByteArray(16)
        groups.forEachIndexed { i, g ->
            val v = g.toInt()
            if (v % 11 != 0 || v / 11 > 0xFFFF) return null
            raw[2 * i] = (v / 11).toByte(); raw[2 * i + 1] = ((v / 11) ushr 8).toByte()
        }
        return MessageDigest.getInstance("SHA-256").digest(raw)
    }

    /** Returns the full-volume encryption key for [secret] (password or recovery password), or null if it unlocks nothing. */
    fun unlock(info: Info, secret: String): ByteArray? {
        val recovery = recoveryInitial(secret)
        for (vmk in info.vmks) {
            val vmkKey: ByteArray? = when {
                vmk.protection == PROTECTION_PASSWORD && vmk.salt != null && recovery == null -> decryptKey(stretch(passwordInitial(secret), vmk.salt), vmk.nonce, vmk.mac, vmk.enc)
                vmk.protection == PROTECTION_RECOVERY && vmk.salt != null && recovery != null -> decryptKey(stretch(recovery, vmk.salt), vmk.nonce, vmk.mac, vmk.enc)
                else -> null
            }
            vmkKey?.let { fvekFrom(info, it) }?.let { return it }
        }
        return null
    }

    /** True when the volume carries a clear-key (suspended protection) VMK, which opens without a secret. */
    fun unlockWithClearKey(info: Info): ByteArray? {
        for (vmk in info.vmks) if (vmk.protection == PROTECTION_CLEAR && vmk.clearKey != null) {
            // clear key VMK: the nested key datum decrypts the nested encrypted VMK
            val key = decryptKey(vmk.clearKey, vmk.nonce, vmk.mac, vmk.enc) ?: continue
            fvekFrom(info, key)?.let { return it }
        }
        return null
    }

    private fun fvekFrom(info: Info, vmkKey: ByteArray): ByteArray? {
        // the metadata hash, encrypted with the VMK, authenticates the whole block (a wrong VMK fails here)
        if (info.validationEnc != null) {
            val dec = aesCcmDecrypt(vmkKey, info.validationNonce!!, info.validationMac!!, info.validationEnc) ?: return null
            if (dec.size < 12 + 32 || u16(dec, 8) != 0x2005 || !dec.copyOfRange(12, 44).contentEquals(info.metadataSha256)) return null
        }
        val key = decryptKey(vmkKey, info.fvekNonce, info.fvekMac, info.fvekEnc) ?: return null
        return key.takeIf { it.size >= info.keyLength && info.keyLength > 0 }?.copyOf(info.keyLength)
    }

    /** AES-CCM-decrypts a key datum and strips its 12-byte header; null on a MAC mismatch or malformed payload. */
    private fun decryptKey(key: ByteArray, nonce: ByteArray?, mac: ByteArray?, enc: ByteArray?): ByteArray? {
        if (nonce == null || mac == null || enc == null || key.size != 32 || enc.size < 12) return null
        val dec = aesCcmDecrypt(key, nonce, mac, enc) ?: return null
        if (u16(dec, 0) != dec.size || dec.size <= 12) return null
        return dec.copyOfRange(12, dec.size)
    }

    /** AES-CCM (tag 16, nonce 12) built on AES-ECB; [tag] is the stored (encrypted) MAC. */
    fun aesCcmDecrypt(key: ByteArray, nonce: ByteArray, tag: ByteArray, ct: ByteArray): ByteArray? {
        val aes = Cipher.getInstance("AES/ECB/NoPadding").apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES")) }
        fun ctr(i: Int): ByteArray {
            val a = ByteArray(16); a[0] = 2
            System.arraycopy(nonce, 0, a, 1, 12)
            a[13] = (i ushr 16).toByte(); a[14] = (i ushr 8).toByte(); a[15] = i.toByte()
            return aes.doFinal(a)
        }
        val pt = ByteArray(ct.size)
        var off = 0; var i = 1
        while (off < ct.size) {
            val ks = ctr(i++); val n = minOf(16, ct.size - off)
            for (k in 0 until n) pt[off + k] = (ct[off + k].toInt() xor ks[k].toInt()).toByte()
            off += n
        }
        val s0 = ctr(0)
        val b0 = ByteArray(16); b0[0] = 0x3A
        System.arraycopy(nonce, 0, b0, 1, 12)
        b0[13] = (pt.size ushr 16).toByte(); b0[14] = (pt.size ushr 8).toByte(); b0[15] = pt.size.toByte()
        var x = aes.doFinal(b0)
        off = 0
        while (off < pt.size) {
            val blk = ByteArray(16)
            System.arraycopy(pt, off, blk, 0, minOf(16, pt.size - off))
            for (k in 0..15) blk[k] = (blk[k].toInt() xor x[k].toInt()).toByte()
            x = aes.doFinal(blk); off += 16
        }
        for (k in 0..15) if ((x[k].toInt() xor s0[k].toInt()).toByte() != tag[k]) return null
        return pt
    }

    // ---- sector cipher ----
    class SectorCipher(private val method: Int, key: ByteArray) {
        private val xts = if (method == 0x8004 || method == 0x8005) AesXts(key) else null
        private val ecb: Cipher?
        private val cbcKey: SecretKeySpec?
        init {
            if (xts == null) {
                cbcKey = SecretKeySpec(key, "AES")
                ecb = Cipher.getInstance("AES/ECB/NoPadding").apply { init(Cipher.ENCRYPT_MODE, cbcKey) }
            } else { cbcKey = null; ecb = null }
        }

        /** Decrypts the 512-byte sector stored at physical sector [sector] (the tweak / IV source). */
        @Synchronized
        fun decrypt(src: ByteArray, srcOff: Int, dst: ByteArray, dstOff: Int, sector: Long) {
            if (xts != null) { xts.decryptUnit(src, srcOff, 512, dst, dstOff, sector); return }
            val iv = ByteArray(16)
            val byteOff = sector * 512
            for (i in 0..7) iv[i] = (byteOff ushr (8 * i)).toByte()
            val ivEnc = ecb!!.doFinal(iv)                          // EBOIV: IV = AES(key, sector byte offset)
            val c = Cipher.getInstance("AES/CBC/NoPadding").apply { init(Cipher.DECRYPT_MODE, cbcKey, IvParameterSpec(ivEnc)) }
            c.doFinal(src, srcOff, 512, dst, dstOff)
        }
    }
}
