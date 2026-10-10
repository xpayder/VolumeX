package app.feldkit

import app.feldkit.storage.crypto.Argon2
import org.junit.Assert.assertEquals
import org.junit.Test

/** RFC 9106 section 5 test vectors (password 32 x 0x01, salt 16 x 0x02, secret 8 x 0x03, associated data 12 x 0x04, t=3, m=32 KiB, p=4, 32-byte tag). */
class Argon2Test {
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    private fun rfc(type: Int) = hex(Argon2.hash(type, ByteArray(32) { 1 }, ByteArray(16) { 2 }, 3, 32, 4, 32, ByteArray(8) { 3 }, ByteArray(12) { 4 }))

    @Test fun argon2d() = assertEquals("512b391b6f1162975371d30919734294f868e3be3984f3c1a13a4db9fabe4acb", rfc(0))
    @Test fun argon2i() = assertEquals("c814d9d1dc7f37aa13f0d77f2494bda1c8de6b016dd388d29952a4c4672b6ce8", rfc(1))
    @Test fun argon2id() = assertEquals("0d640df58d78766c08c037a34a8b53c9d01ef0452d75b65eb52520e96b01e659", rfc(2))
}
