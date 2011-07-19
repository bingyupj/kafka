/*
 * Copyright 2010 LinkedIn
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package kafka.message

import java.nio._
import java.nio.channels._
import java.util.zip.CRC32
import java.util.UUID
import kafka.utils._

/**
 * Message byte offsets
 */
object Message {
  val CurrentMagicValue: Byte = 1
  val MagicOffset = 0
  val MagicLength = 1
  val AttributeOffset = MagicOffset + MagicLength
  val AttributeLength = 1

  /**
   * Computes the CRC value based on the magic byte
   * @param magic Specifies the magic byte value. Possible values are 0 and 1
   *              0 for no compression
   *              1 for compression
  */
  def CrcOffset(magic: Byte): Int = magic match {
    case 0 => MagicOffset + MagicLength
    case _ => AttributeOffset + AttributeLength
  }
  
  val CrcLength = 4

  /**
   * Computes the offset to the message payload based on the magic byte
   * @param magic Specifies the magic byte value. Possible values are 0 and 1
   *              0 for no compression
   *              1 for compression
   */
  def PayloadOffset(magic: Byte): Int = CrcOffset(magic) + CrcLength

  /**
   * Computes the size of the message header based on the magic byte
   * @param magic Specifies the magic byte value. Possible values are 0 and 1
   *              0 for no compression
   *              1 for compression
   */
  def HeaderSize(magic: Byte): Int = PayloadOffset(magic)

  /**
   * Size of the header for magic byte 0. This is the minimum size of any message header
   */
  val MinHeaderSize = HeaderSize(0);

  /**
   * Specifies the mask for the compression code. 2 bits to hold the compression codec.
   * 0 is reserved to indicate no compression
   */
  val CompressionCodeMask: Int = 0x03  //
  
  
  val NoCompression:Int = 0
}

/**
 * A message. The format of an N byte message is the following:
 *
 * If magic byte is 0
 *
 * 1. 1 byte "magic" identifier to allow format changes
 *
 * 2. 4 byte CRC32 of the payload
 *
 * 3. N - 5 byte payload
 *
 * If magic byte is 1
 *
 * 1. 1 byte "magic" identifier to allow format changes
 *
 * 2. 1 byte "attributes" identifier to allow annotations on the message independent of the version (e.g. compression enabled, type of codec used)
 *
 * 3. 4 byte CRC32 of the payload
 *
 * 4. N - 6 byte payload
 * 
 */
class Message(val buffer: ByteBuffer) {
  
  import kafka.message.Message._
    
  
  private def this(checksum: Long, bytes: Array[Byte], compressionCodec: CompressionCodec) = {
    this(ByteBuffer.allocate(Message.HeaderSize(Message.CurrentMagicValue) + bytes.length))
    buffer.put(CurrentMagicValue)
    var attributes:Byte = 0
    if (compressionCodec.codec > 0) {
      attributes =  (attributes | (Message.CompressionCodeMask & compressionCodec.codec)).toByte
    }
    buffer.put(attributes)
    Utils.putUnsignedInt(buffer, checksum)
    buffer.put(bytes)
    buffer.rewind()
  }

  def this(checksum:Long, bytes:Array[Byte]) = this(checksum, bytes, NoCompressionCodec)
  
  def this(bytes: Array[Byte], compressionCodec: CompressionCodec) = {
    //Note: we're not crc-ing the attributes header, so we're susceptible to bit-flipping there
    this(Utils.crc32(bytes), bytes, compressionCodec)
  }

  def this(bytes: Array[Byte]) = this(bytes, NoCompressionCodec)
  
  def size: Int = buffer.limit
  
  def payloadSize: Int = size - HeaderSize(magic)
  
  def magic: Byte = buffer.get(MagicOffset)
  
  def attributes: Byte = buffer.get(AttributeOffset)
  
  def compressionCodec:CompressionCodec =
    CompressionCodec.getCompressionCodec(buffer.get(AttributeOffset) & CompressionCodeMask)

  def checksum: Long = Utils.getUnsignedInt(buffer, CrcOffset(magic))
  
  def payload: ByteBuffer = {
    var payload = buffer.duplicate
    payload.position(HeaderSize(magic))
    payload = payload.slice()
    payload.limit(payloadSize)
    payload.rewind()
    payload
  }
  
  def isValid: Boolean = 
    checksum == Utils.crc32(buffer.array, buffer.position + buffer.arrayOffset + PayloadOffset(magic), payloadSize)
     
  def serializedSize: Int = 4 /* int size*/ + buffer.limit
   
  def serializeTo(serBuffer:ByteBuffer) = {
    serBuffer.putInt(buffer.limit)
    serBuffer.put(buffer.duplicate)
  }

  override def toString(): String = 
    "message(magic = " + magic + ", attributes = " + attributes + ", crc = " + checksum + 
    ", payload = " + payload + ")"
  
  override def equals(any: Any): Boolean = {
    any match {
      case that: Message => size == that.size && attributes == that.attributes && checksum == that.checksum && payload == that.payload && magic == that.magic
      case _ => false
    }
  }
  
  override def hashCode(): Int = buffer.hashCode
  
}
