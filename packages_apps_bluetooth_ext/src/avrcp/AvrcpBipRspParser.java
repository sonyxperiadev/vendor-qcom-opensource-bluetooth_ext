/*
 * Copyright (c) 2015-2016, The Linux Foundation. All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are
 * met:
 *           * Redistributions of source code must retain the above copyright
 *             notice, this list of conditions and the following disclaimer.
 *           * Redistributions in binary form must reproduce the above
 *           * copyright notice, this list of conditions and the following
 *             disclaimer in the documentation and/or other materials provided
 *             with the distribution.
 *           * Neither the name of The Linux Foundation nor the names of its
 *             contributors may be used to endorse or promote products derived
 *             from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED "AS IS" AND ANY EXPRESS OR IMPLIED
 * WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NON-INFRINGEMENT
 * ARE DISCLAIMED.  IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS
 * BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR
 * BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,
 * WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE
 * OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN
 * IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package com.android.bluetooth.avrcp;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.StringWriter;
import java.io.UnsupportedEncodingException;
import java.util.HashMap;
import java.util.Objects;
import java.util.Random;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlPullParserFactory;
import org.xmlpull.v1.XmlSerializer;

import com.android.internal.util.FastXmlSerializer;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteException;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.text.TextUtils;
import android.util.Log;
import android.util.Size;

public class AvrcpBipRspParser {
    private String TAG = "AvrcpBipParser";

    private final boolean D = true;
    private static final boolean V = AvrcpBipRsp.V;
    private Context mContext;
    private HashMap<Long, String> mArtHandleMap = new HashMap<Long, String>();
    private HashMap<String, AvrcpBipRspCoverArtAttributes > mCoverArtAttributesMap =
        new HashMap<String, AvrcpBipRspCoverArtAttributes >();
    private final Uri URI_ALBUM = MediaStore.Audio.Albums.EXTERNAL_CONTENT_URI;
    private final Uri URI_MEDIA = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
    private int BIP_THUMB_WIDTH = 200;
    private int BIP_THUMB_HEIGHT = 200;
    private int MIN_SUPPORTED_WIDTH = 100;
    private int MIN_SUPPORTED_HEIGHT = 100;
    private int MAX_SUPPORTED_WIDTH = 1280;
    private int MAX_SUPPORTED_HEIGHT = 1080;
    private int MAX_IMG_HANDLE = 10000000;
    private int COMPRESSION_QUALITY_HIGH = 75;
    /* Constants used for converting RGB -> YUV */
    private final int COEFF1 = 19595;
    private final int COEFF2 = 38470;
    private final int COEFF3 = 7471;
    private final int COEFF4 = -11059;
    private final int COEFF5 = -21709;
    private final int COEFF6 = 32768;
    private final int COEFF7 = 32768;
    private final int COEFF8 = -27439;
    private final int COEFF9 = -5329;
    /* Tmp path for storing file before compressing and sending to obex.
     * This file is deleted after the operation */
    private String mTmpFilePath = "";
    private final String ORDER_BY = MediaStore.Audio.Albums.DEFAULT_SORT_ORDER;
    private final String[] COL_ALBUM = new String[] {
             MediaStore.Audio.Albums._ID,
             MediaStore.Audio.Albums.ALBUM_ID,
    };
    private final String WHERE = MediaStore.Audio.Albums.ALBUM + "=?";
    private String tag = "";

    public AvrcpBipRspParser(Context context, String tag) {
        setTag(tag);
        mContext = context;
        mArtHandleMap.clear();
        mCoverArtAttributesMap.clear();
        this.tag = tag;
        isStorageReady();
    }

    private void setTag(String tag){
        try {
            TAG += "_" + tag.replaceAll(":","").substring(6,12);
        } catch (Exception e) {
            Log.w(TAG, "tag generation failed ", e);
        }
    }

    private class AvrcpBipRspCoverArtAttributes {
        private String mVersion = "1.0";
        private String mNativeEncoding = "";
        private String mNativePixel = "";
        private String mNativeSize = "";
        private long albumId = 0;
        private String mArtPath = null;
        private int mWidth = 0;
        private int mHeight = 0;
        private long albumUriId = 0;

        Uri getmAlbumUri() {
            if (albumId != 0) {
                return ContentUris.withAppendedId(URI_ALBUM, albumId);
            } else {
                return null;
            }
        }

        public long getAlbumUriId() {
            return albumUriId;
        }
        public void setAlbumUriId(long albumUriId) {
            this.albumUriId = albumUriId;
        }
        public void setAlbumId(long albumId) {
            this.albumId = albumId;
        }
        public void setWidth(int width) {
            mWidth = width;
        }
        public void setHeight(int height) {
            mHeight = height;
        }
        public void setArtPath(String artPath) {
            mArtPath = artPath;
        }
        public void setNativeEncoding(String nativeEncoding) {
            mNativeEncoding = nativeEncoding;
        }
        public void setNativePixel(String nativePixel) {
            mNativePixel = nativePixel;
        }
        public void setNativeSize(String nativeSize) {
            mNativeSize = nativeSize;
        }
        public long getAlbumId() {
            return albumId;
        }
        public int getWidth() {
            return mWidth;
        }
        public int getHeigth() {
            return mHeight;
        }
        public String getArtPath() {
            return mArtPath;
        }
        public String getVersion() {
            return mVersion;
        }
        public String getNativeEncoding() {
            return mNativeEncoding;
        }
        public String getNativePixel() {
            return mNativePixel;
        }
        public String getNativeSize() {
            return mNativeSize;
        }
    };

    private void rgb2yuv(int rgb, byte[] convArray) {
        int a = Color.red(rgb);
        int b = Color.green(rgb);
        int c = Color.blue(rgb);
        convArray[0] = (byte) ((COEFF1 * a + COEFF2 * b + COEFF3 * c) >> 16);
        convArray[1] = (byte) (((COEFF4 * a + COEFF5 * b + COEFF6 * c) >> 16) + 128);
        convArray[2] = (byte) (((COEFF7 * a + COEFF8 * b + COEFF9 * c) >> 16) + 128);
    }

    private byte[] convertToYuv(int[] rgb, int w, int h) {
        byte[] convArray = new byte[w * h * ImageFormat.getBitsPerPixel(ImageFormat.YUY2)];
        byte[] col0 = new byte[3];
        byte[] co11 = new byte[3];
        for (int i = 0; i < h; ++i) {
            for (int j = 0; j < w; j += 2) {
                int id = i * w + j;
                rgb2yuv(rgb[id], col0);
                rgb2yuv(rgb[id + 1], co11);
                int index = id / 2 * 4;
                convArray[index] = col0[0];
                convArray[index + 1] = col0[1];
                convArray[index + 2] = co11[0];
                convArray[index + 3] = col0[2];
           }
        }

        return convArray;
    }

    private Bitmap getScaledBitmap(Uri uri, int w, int h) {
        ContentResolver res = mContext.getContentResolver();
        Bitmap bitmap = null;
        if (D) {
            Log.d(TAG, " getScaledBitmap uri :" + uri + " w :" + w + " h :" + h);
        }
        if (res != null && uri != null) {
            try {
                bitmap = res.loadThumbnail(uri,new Size(w, h), null);
                if (bitmap != null){
                    // rescale to exactly the size we need
                    if (bitmap.getWidth() != w || bitmap.getHeight() != h){
                        Bitmap tmp = Bitmap.createScaledBitmap(bitmap, w, h, true);
                        if(tmp != bitmap){
                            bitmap.recycle();
                            Log.v(TAG," getScaledBitmap bitmap recycled");
                        }
                        bitmap = tmp;
                    }
                }
            } catch (IOException e) {
                Log.w(TAG, "getScaledBitmap  " + e.toString());
                e.printStackTrace();
            }
        }
        if (D) Log.d(TAG, "Exit getScaledBitmap");
        return bitmap;
    }

    private long getArtHandleFromAlbum (String albumName) {
        long artHandle = -1;
        String whereVal [] = { albumName };
        Cursor cursor = null;
        if (D) Log.d(TAG, "getArtHandleFromAlbum :" + albumName);
        try {
            cursor = mContext.getContentResolver().query(URI_ALBUM,
                    COL_ALBUM, WHERE, whereVal, ORDER_BY);
            if ((cursor != null) && cursor.moveToFirst()) {
                artHandle =  cursor.getLong(1);
                if ( !isAlbumArtPresent(artHandle) ) {
                    artHandle = -1;
                }
            }
            if (V) Log.v(TAG," artHandle :" + artHandle);
        } catch (SQLiteException e) {
            Log.e(TAG, "getArtHandleFromAlbum: " + e);
        } finally {
            if (cursor != null)
                cursor.close();
        }
        return artHandle;
    }

    private String getImgHandleFromArtHandle(long artHandle) {
        if (V) Log.v(TAG, "getImgHandleFromArtHandle: artHandle :"
                + artHandle +" map : " + mArtHandleMap);
        if (mArtHandleMap.containsKey(artHandle)) {
            return mArtHandleMap.get(artHandle);
        } else {
            Random rnd = new Random();
            int val = rnd.nextInt(MAX_IMG_HANDLE);
            String imgHandle = String.format("%07d", val);
            if (V) Log.v(TAG,"imgHandle = " + imgHandle);
            AvrcpBipRspCoverArtAttributes coverArtAttributes = new AvrcpBipRspCoverArtAttributes();
            coverArtAttributes.setAlbumId(artHandle);
            if (V) Log.v(TAG,"getImgHandleFromArtHandle imgHandle :"
                + imgHandle);
            mCoverArtAttributesMap.put(imgHandle, coverArtAttributes);
            mArtHandleMap.put(artHandle, imgHandle);
            return imgHandle;
        }
    }

    private void updateExifHeader(String imgHandle, int width, int height) {

        AvrcpBipRspCoverArtAttributes artAttributes = mCoverArtAttributesMap.get(imgHandle);
        if (artAttributes == null) {
            Log.e(TAG, "updateExifHeader artAttributes null");
            return;
        }
        try {
            ExifInterface newexif = new ExifInterface(mTmpFilePath);
            //Need to update it, with your new height width
            newexif.setAttribute(ExifInterface.TAG_IMAGE_WIDTH, width+"");
            newexif.setAttribute(ExifInterface.TAG_IMAGE_LENGTH, height+"");
            newexif.saveAttributes();
        } catch (IOException e) {
            Log.e(TAG,"readImgProperties: exception" + e);
        }
     }

    private void readImgProperties(String imgHandle) {
        if (D) Log.d(TAG,"readImgProperties");
        AvrcpBipRspCoverArtAttributes artAttributes = mCoverArtAttributesMap.get(imgHandle);
        ContentResolver cr = mContext.getContentResolver();
        if (artAttributes == null || cr == null) {
            if (D) Log.d(TAG,"readImgProperties  artAttributes "
                + artAttributes + " cr :" + cr);
            return;
        }
        Bitmap bitMap = null;
        try {
            bitMap = cr.loadThumbnail(artAttributes.getmAlbumUri(),
                    new Size(MAX_SUPPORTED_WIDTH, MAX_SUPPORTED_HEIGHT),null);
        } catch (IOException e) {
            e.printStackTrace();
        }
        if (bitMap == null) {
            Log.w(TAG,"readImgProperties bitMap null ");
            return;
        }
        int w = bitMap.getWidth();
        int h = bitMap.getHeight();
        String size = Integer.toString(bitMap.getByteCount());
        bitMap.recycle();
        bitMap = null;
        long albumId = artAttributes.getAlbumId();
        AvrcpBipRspCoverArtAttributes coverArtAttributes = new AvrcpBipRspCoverArtAttributes();
        coverArtAttributes.setAlbumId(albumId);
        coverArtAttributes.setWidth(w);
        coverArtAttributes.setHeight(h);
        // DEFAULT encoding as JPEG
        coverArtAttributes.setNativeEncoding("JPEG");
        coverArtAttributes.setNativeSize(size);
        coverArtAttributes.setNativePixel(w + "*" + h);
        mCoverArtAttributesMap.put(imgHandle, coverArtAttributes);
        if (V) Log.v(TAG," albumId : " + albumId + " imgHandle : "
                + imgHandle + " w : " + w + " H : " + h + " s: " + size);
    }

    private class AvrcpBipRspImgDescriptor {
        public String mVersion;
        public String mEncoding;
        public String mPixel;
        public String mMaxSize;
        public String mTransformation;
        public static final String DEFAULT_VERSION = "1.0";
        private String DEFAULT_ENCODING = "JPEG";
        private String DEFAULT_PIXEL = "";

        private AvrcpBipRspImgDescriptor(HashMap<String, String> attrs) {
            mVersion = attrs.get("version");
            mEncoding = attrs.get("encoding");
            mPixel = attrs.get("pixel");
            mMaxSize = attrs.get("maxsize");
            mTransformation = attrs.get("transformation");
        }

        public AvrcpBipRspImgDescriptor() {
            mVersion = DEFAULT_VERSION;
            mEncoding = DEFAULT_ENCODING;
            mPixel = DEFAULT_PIXEL;
        }

        @Override
        public String toString() {
            return "AvrcpBipRspImgDescriptor [mVersion=" + mVersion + ", mEncoding="
                + mEncoding + ", mPixel=" + mPixel  + ", mMaxSize=" + mMaxSize
                + ", mTransformation=" + mTransformation + ", DEFAULT_ENCODING="
                + DEFAULT_ENCODING + ", DEFAULT_PIXEL=" + DEFAULT_PIXEL + "]";
        }
    };

    private AvrcpBipRspImgDescriptor parseImgDescXml(String imgDescXml) {
        AvrcpBipRspImgDescriptor ev = null;
        if (imgDescXml == null)
            return ev;
        if (D) Log.d(TAG, "parseImgDescXml");
        try {
            InputStream is = new ByteArrayInputStream(imgDescXml.getBytes("UTF-8"));
            try {
                XmlPullParser imgDescParser = XmlPullParserFactory.newInstance().newPullParser();
                imgDescParser.setInput(is, "UTF-8");

                HashMap<String, String> attrs = new HashMap<String, String>();
                int event = imgDescParser.getEventType();
                while (event != XmlPullParser.END_DOCUMENT) {
                    switch (event) {
                        case XmlPullParser.START_TAG:
                            if (imgDescParser.getName().equals("image-descriptor")) {
                                if (V) Log.v(TAG, "image-descriptor version: " +
                                    imgDescParser.getAttributeValue(0));
                                attrs.put(imgDescParser.getAttributeName(0),
                                    imgDescParser.getAttributeValue(0));
                            }

                            if (imgDescParser.getName().equals("image")) {
                                for (int i = 0; i < imgDescParser.getAttributeCount(); i++) {
                                    attrs.put(imgDescParser.getAttributeName(i),
                                        imgDescParser.getAttributeValue(i));
                                    if (V) Log.v(TAG, "image " + imgDescParser.getAttributeName(i) +
                                        " " + imgDescParser.getAttributeValue(i));
                                }
                                ev = new AvrcpBipRspImgDescriptor(attrs);
                                // return immediately, only one event should be here
                                return ev;
                            }
                            break;
                    }

                    event = imgDescParser.next();
                }
                if (V) Log.v(TAG, "attrs " + attrs);

            } catch (XmlPullParserException | IOException | IllegalArgumentException e) {
                Log.e(TAG, "Error when parsing XML", e);
            }
        } catch (UnsupportedEncodingException e) {
            Log.e(TAG, "UnsupportedEncodingException", e);
        }
        if (D) Log.d(TAG, "parseImgDescXml returning " + ev);
        Log.d(TAG,"Exit parseImgDescXml");
        return ev;
    }

    private AvrcpBipRspImgDescriptor validateImgDescriptor(AvrcpBipRspImgDescriptor imgDesc) {
        int width = 0, height = 0;
        int minWidth = 0, minHeight = 0;
        int maxWidth = 0, maxHeigth = 0;
        AvrcpBipRspImgDescriptor imgDes = imgDesc;

        if (V) {
            Log.v(TAG, "validateImgDescriptor :" + imgDesc);
        } else {
            Log.d(TAG, "validateImgDescriptor ");
        }
        /* check if the pixel field is range or discrete */
        if ((imgDesc.mPixel.indexOf("-") == -1)) {
            /* Check if the pixel fields contains only 1 "*". */
            if ((imgDesc.mPixel.indexOf("*") == -1) ||
                (imgDesc.mPixel.indexOf("*") != imgDesc.mPixel.lastIndexOf("*"))) {
                Log.e(TAG, "validateImgDescriptor: pixel field is invalid: " +  imgDesc.mPixel);
                return null;
            }
            try {
                width = Integer.parseInt(imgDesc.mPixel.substring(
                        0, imgDesc.mPixel.indexOf("*")));
                height = Integer.parseInt(imgDesc.mPixel.substring(
                        imgDesc.mPixel.lastIndexOf("*") + 1));
                if ((width < MIN_SUPPORTED_WIDTH || width > MAX_SUPPORTED_WIDTH) ||
                    ((height < MIN_SUPPORTED_HEIGHT || height > MAX_SUPPORTED_HEIGHT))) {
                    Log.e(TAG, "validateImgDescriptor: pixel width or height is " +
                        "invalid: " +  imgDesc.mPixel);
                    return null;
                }
            } catch (NumberFormatException e) {
                Log.e(TAG, "exception while parsing pixel: " + imgDesc.mPixel);
                return null;
            }
        } else {
            /* check if the pixel range contains only single "-" */
            if (imgDesc.mPixel.indexOf("-") != imgDesc.mPixel.lastIndexOf("-")) {
                Log.e(TAG, "validateImgDescriptor: Invalid pixel range");
                return null;
            }
            /* Pixel is specified as range from client, parse it */
            /* Split the strings before and after "-" */
            String minRange = imgDesc.mPixel.substring(0, imgDesc.mPixel.indexOf("-"));
            String maxRange = imgDesc.mPixel.substring(imgDesc.mPixel.indexOf("-") + 1);
            Log.d(TAG, "minRange: " +  minRange + " maxRange: " + maxRange);
            try {
                minWidth = Integer.parseInt(minRange.substring(0,
                    minRange.indexOf("*")));
                minHeight = Integer.parseInt(minRange.substring(
                    minRange.lastIndexOf("*") + 1));
                maxWidth = Integer.parseInt(maxRange.substring(
                    0, maxRange.indexOf("*")));
                maxHeigth = Integer.parseInt(maxRange.substring(
                    maxRange.lastIndexOf("*") + 1));
                if ((maxWidth < minWidth || maxHeigth < minHeight)) {
                    Log.e(TAG, "validateImgDescriptor: Invalid pixel range: " + minRange + " - " +
                        maxRange);
                    return null;
                } else if ((maxWidth < MIN_SUPPORTED_WIDTH || minWidth > MAX_SUPPORTED_WIDTH) ||
                    ((maxHeigth < MIN_SUPPORTED_HEIGHT || minHeight > MAX_SUPPORTED_HEIGHT))) {
                    Log.e(TAG, "validateImgDescriptor: Invalid pixel range: " + minRange + " - " +
                        maxRange);
                    return null;
                } else if ((minWidth >= MIN_SUPPORTED_WIDTH) &&
                    (minHeight >= MIN_SUPPORTED_HEIGHT) && (maxWidth > MAX_SUPPORTED_WIDTH) &&
                    (maxHeigth > MAX_SUPPORTED_HEIGHT)) {
                    width = MAX_SUPPORTED_WIDTH;
                    height = MAX_SUPPORTED_HEIGHT;
                } else if ((minWidth < MIN_SUPPORTED_WIDTH) &&
                    (minHeight < MIN_SUPPORTED_HEIGHT) && (maxWidth >= MAX_SUPPORTED_WIDTH) &&
                    (maxHeigth >= MAX_SUPPORTED_HEIGHT)) {
                    width = MAX_SUPPORTED_WIDTH;
                    height = MAX_SUPPORTED_HEIGHT;
                } else if ((minWidth >= MIN_SUPPORTED_WIDTH) &&
                    (minHeight >= MIN_SUPPORTED_HEIGHT) && (maxWidth < MAX_SUPPORTED_WIDTH) &&
                    (maxHeigth < MAX_SUPPORTED_HEIGHT)) {
                    width = maxWidth;
                    height = maxHeigth;
                } else if ((minWidth < MIN_SUPPORTED_WIDTH) &&
                    (minHeight < MIN_SUPPORTED_HEIGHT) && (maxWidth <= MAX_SUPPORTED_WIDTH) &&
                    (maxHeigth <= MAX_SUPPORTED_HEIGHT)) {
                    width = maxWidth;
                    height = maxHeigth;
                } else {
                    Log.e(TAG, "validateImgDescriptor: Invalid pixel range: " + minRange + " - " +
                        maxRange);
                    return null;
                }
            } catch (NumberFormatException e) {
                Log.e(TAG, "exception while parsing pixel range: " + minRange + " - " +
                        maxRange);
                return null;
            }
        }
        if (imgDesc.mTransformation != null &&
            !((imgDesc.mTransformation.equals("stretch")) ||
            (imgDesc.mTransformation.equals("fill")) ||
            (imgDesc.mTransformation.equals("crop")))) {
            Log.e(TAG, "validateImgDescriptor: transformation is invalid: " +
                imgDesc.mTransformation);
            return null;
        }
        if (V) Log.v(TAG, "validateImgDescriptor: before imgDesc.mPixel = " + imgDesc.mPixel);
        /* Update the pixel value now */
        imgDes.mPixel = width + "*" + height;
        if (D) Log.d(TAG, "validateImgDescriptor: imgDesc.mPixel = " + imgDes.mPixel);
        return imgDes;
    }

    boolean getImgThumb(OutputStream out, String imgHandle) {
        boolean retVal = false;
        AvrcpBipRspCoverArtAttributes artAttributes = mCoverArtAttributesMap.get(imgHandle);
        if (artAttributes == null) {
            Log.w(TAG, "getImgThumb: imageHandle =" +  imgHandle + " is not in hashmap");
            return false;
        }
        if (!isStorageReady()) {
            Log.e(TAG, "getImgThumb: Storage not mounted ");
            return false;
        }
        Bitmap bm = getScaledBitmap(artAttributes.getmAlbumUri(),
                BIP_THUMB_WIDTH, BIP_THUMB_HEIGHT);
        FileOutputStream tmp = null;
        FileInputStream tmp1 = null;
        if (bm != null) {
            try {

                int[] pixelArray = new int[BIP_THUMB_WIDTH * BIP_THUMB_HEIGHT];
                // Copy pixel data from the Bitmap into integer pixelArray
                bm.getPixels(pixelArray, 0, BIP_THUMB_WIDTH, 0, 0, BIP_THUMB_WIDTH,
                    BIP_THUMB_HEIGHT);
                byte[] yuvArray = convertToYuv(pixelArray, BIP_THUMB_WIDTH,
                        BIP_THUMB_HEIGHT);
                /* Convert Pixel Array to YuvImage */
                YuvImage yuvImg = new YuvImage(yuvArray, ImageFormat.YUY2,
                            BIP_THUMB_WIDTH, BIP_THUMB_HEIGHT, null);
                // Use temp file as ExifInterface requires absolute path of storage
                // file to update headers
                try {
                    tmp = new FileOutputStream(mTmpFilePath);
                } catch (FileNotFoundException e) {
                    Log.w(TAG,"getImgThumb: unable to open tmp File for writing");
                    return retVal;
                }
                if (D) Log.d(TAG,"getImgThumb: compress +");
                /* Compress YuvImage in YCC422 sampling using JPEG compression */
                yuvImg.compressToJpeg(new Rect(0, 0, BIP_THUMB_WIDTH, BIP_THUMB_HEIGHT),
                    COMPRESSION_QUALITY_HIGH, tmp);
                if (D) Log.d(TAG,"getImgThumb: compress -");
                tmp.flush();
                /* replace JFIF header with EXIF header and update new pixel size */
                updateExifHeader(imgHandle, BIP_THUMB_WIDTH, BIP_THUMB_HEIGHT);
                /* Copy the new updated header file to OutputStream */
                try {
                    tmp1 = new FileInputStream(mTmpFilePath);
                } catch (FileNotFoundException e) {
                    Log.w(TAG,"getImgThumb: unable to open tmp File for reading");
                    return retVal;
                }
                byte[] buffer = new byte[4096]; // To hold file contents
                int bytes_read;
                while ((bytes_read = tmp1.read(buffer)) != -1)
                    out.write(buffer, 0, bytes_read);
                /* Flush the data to output stream */
                out.flush();
                retVal = true;
            } catch (Exception e) {
                Log.w(TAG, "Exception = " + e);
            } finally {
                try {
                    try {
                        if (tmp != null) {
                            tmp.close();
                        }
                    } catch (IOException e) {
                        Log.w(TAG,"getImgThumb: exception in closing file");
                    }
                    try {
                        if (tmp1 != null) {
                            tmp1.close();
                        }
                    } catch (IOException e) {
                        Log.w(TAG,"getImgThumb: exception in closing file");
                    }
                    if (out != null) {
                        out.close();
                    }
                    /* Delete the tmp file now */
                    delTmpFile();
                } catch (IOException e) {
                    Log.w(TAG, "Exception = " + e);
                }
            }
        }
        if (D) Log.d(TAG, "getImgThumb: returning " + retVal);
        return retVal;
    }

    boolean getImg(OutputStream out, String imgHandle,
                    String imgDescXmlString) {

        boolean retVal = false;

        if (mCoverArtAttributesMap.get(imgHandle) == null) {
            Log.w(TAG, "getImg: imageHandle =" +  imgHandle + " is not in hashmap");
            return retVal;
        }
        if (!isStorageReady()) {
            Log.e(TAG, "getImg: Storage not mounted ");
            return false;
        }
        if(D) Log.d(TAG,"Enter getImg");
        AvrcpBipRspImgDescriptor imgDesc = new AvrcpBipRspImgDescriptor();

        /* Read the Properties of Image as per the image Handle */
        readImgProperties(imgHandle);

        if (imgDescXmlString != null) {
            /* As per SPEC, image-descriptor can be empty */
            if (!(imgDescXmlString.equals(""))) {
                imgDesc = parseImgDescXml(imgDescXmlString);
                if (imgDesc == null) {
                    Log.w(TAG, " getImg imgDesc null");
                    return retVal;
                }
                if (!(imgDesc.mVersion.equals(AvrcpBipRspImgDescriptor.DEFAULT_VERSION) &&
                    imgDesc.mEncoding != null && imgDesc.mPixel != null)) {
                    Log.w(TAG, " getImg imgDesc not valid :" + imgDesc);
                    return retVal;
                }
            }
        }

        Bitmap.CompressFormat cmpFormat;
        int width;
        int height;
        FileOutputStream tmp = null;
        FileInputStream tmp1 = null;

        if (V) Log.v(TAG,"getImg: imgDesc.mPixel = " + imgDesc.mPixel);
        if (imgDesc.mPixel.equals("")) {
            // remote device is willing to accept any pixel values, send
            // the default pixels of image
            width = mCoverArtAttributesMap.get(imgHandle).getWidth();
            height = mCoverArtAttributesMap.get(imgHandle).getHeigth();
        } else {
            imgDesc = validateImgDescriptor(imgDesc);
            if (imgDesc != null) {
                try {
                    //Get Width and Height from Image Descriptor Object
                    width = Integer.parseInt(imgDesc.mPixel.substring(
                            0, imgDesc.mPixel.indexOf("*")));
                    height = Integer.parseInt(imgDesc.mPixel.substring(
                            imgDesc.mPixel.lastIndexOf("*") + 1));
                } catch (NumberFormatException e) {
                    Log.e(TAG, "exception while parsing pixel");
                    return retVal;
                }
            } else {
                /* Image descriptor cannot be parsed to valid fields */
                Log.e(TAG,"getImg: imgDesc is not valid!!");
                return retVal;
            }
        }

        if (D) Log.d(TAG,"getImg: imgHandle = " + imgHandle + " width = "
            + width + " height = " + height + " encoding = " +
                imgDesc.mEncoding);

        if (imgDesc.mEncoding.equals("")) {
            // remote device is willing to accept any encoding, send
            // the default encoding of image
            cmpFormat = Bitmap.CompressFormat.JPEG;
        } else {
            // supported encoding is only JPEG and PNG
            if (imgDesc.mEncoding.toUpperCase().equals("JPEG"))
                cmpFormat = Bitmap.CompressFormat.JPEG;
            else if (imgDesc.mEncoding.toUpperCase().equals("PNG"))
                cmpFormat = Bitmap.CompressFormat.PNG;
            else
                return retVal;
        }

        Uri uri = mCoverArtAttributesMap.get(imgHandle).getmAlbumUri();
        if (D) Log.d(TAG,"getImg: getScaledBitmap ");
        Bitmap bm = getScaledBitmap(uri, width, height);
        if (bm != null) {
            try {
                // Use temp file as ExifInterface requires absolute path of storage
                // file to update headers
                try {
                    tmp = new FileOutputStream(mTmpFilePath);
                } catch (FileNotFoundException e) {
                    Log.w(TAG,"getImg: unable to open tmp File for writing");
                    return retVal;
                }
                if (D) Log.d(TAG,"getImg: compress +");
                bm.compress(cmpFormat, COMPRESSION_QUALITY_HIGH, tmp);
                if (D) Log.d(TAG,"getImg: compress -");
                try {
                    if (tmp != null) {
                        tmp.flush();
                        tmp.close();
                    }
                } catch (IOException e) {
                     Log.e(TAG,"getImg: exception in closing file");
                     return retVal;
                }
                File f = new File(mTmpFilePath);
                if (f != null && f.isFile() && f.exists()) {
                    if (D) Log.d(TAG, "File Size = " + f.length());
                    /* check if the size of compressed file is within range of maxsize */
                    if (imgDesc.mMaxSize != null &&
                        f.length() > Long.valueOf(imgDesc.mMaxSize)) {
                        Log.w(TAG, "Image size using compression is " +
                            f.length() + " more than maxsize = " +
                            imgDesc.mMaxSize + " deleting the file");
                        f.delete();
                        return retVal;
                    }
                }
                /* Copy the new updated header file to OutputStream */
                try {
                    tmp1 = new FileInputStream(mTmpFilePath);
                } catch (FileNotFoundException e) {
                    Log.w(TAG,"getImg: unable to open tmp File for reading");
                    return retVal;
                }
                byte[] buffer = new byte[4096]; // To hold file contents
                int bytes_read;
                while ((bytes_read = tmp1.read(buffer)) != -1)
                    out.write(buffer, 0, bytes_read);
                /* Flush the data to output stream */
                out.flush();
                retVal = true;
            } catch (Exception e) {
                Log.e(TAG, "Exception = " + e);
            } finally {
                try {
                    try {
                        if (tmp != null) {
                            tmp.close();
                        }
                    } catch (IOException e) {
                         Log.e(TAG,"getImg: exception in closing file");
                    }
                    try {
                        if (tmp1 != null) {
                            tmp1.close();
                        }
                    } catch (IOException e) {
                         Log.e(TAG,"getImg: exception in closing file");
                    }
                    if (out != null) {
                        out.close();
                    }
                    /* Delete the tmp file now */
                    delTmpFile();
                } catch (IOException e) {
                    Log.e(TAG, "Exception = " + e);
                }
            }
        }
        if (D) Log.d(TAG,"getImg: returning " + retVal);
        return retVal;
    }

    boolean isImgHandleValid(String imgHandle) {
        if (mArtHandleMap.containsValue(imgHandle)) {
            return true;
        }
        return false;
    }

    String getImgHandleFromTitle(String title) {
        String albumName = getAlbumName(title);
        if (!TextUtils.isEmpty(albumName)) {
            return getImgHandle(albumName);
        }
        if (D) Log.d(TAG, " getImgHandleFromTitle, not found");
        return "";
    }

    String getImgHandle(String albumName) {
        try {
            long artHandle = getArtHandleFromAlbum(albumName);
            if (artHandle != -1) {
                String imgHandle = getImgHandleFromArtHandle(artHandle);
                if (D) Log.d(TAG,"getImgHandle: imgHandle = " + imgHandle);
                return imgHandle;
            }
        } catch (IllegalArgumentException e) {
            Log.w(TAG,"getImgHandle: IllegalArgumentException = ", e);
        }
        if (D) Log.d(TAG, "getImgHandle: returning empty");
        return "";
    }

    private String getAlbumName (String songName) {
        String albumName = null;
        String[] mCursorCols = new String[] {
                        MediaStore.Audio.Media._ID,
                        MediaStore.Audio.Media.ALBUM,
                        MediaStore.Audio.Media.TITLE,
        };

        String where = MediaStore.Audio.Media.IS_MUSIC + "=? AND " +
                MediaStore.Audio.Media.TITLE + "= ? ";

        String queryValues[]= {"1", songName};

        Cursor cursor = null;

        try {
            cursor = mContext.getContentResolver().query(
            URI_MEDIA, mCursorCols,
            where, queryValues, ORDER_BY);
            if (cursor != null && cursor.moveToFirst()) {
                albumName = cursor.getString(1);
            }
        } catch (IllegalArgumentException | SQLiteException e) {
            Log.e(TAG, "getAlbumName: exception = " + e);
        } finally {
            if (cursor != null)
                cursor.close();
        }
        if (D) Log.d(TAG, "albumName : " + albumName);
        return albumName;
    }

    /* Encode the Image Properties into the StringBuilder reference.
     * */
    byte[] encode(String imgHandle)
            throws IllegalArgumentException, IllegalStateException, IOException
    {
        if (D) Log.d(TAG,"Enter encode");
        readImgProperties(imgHandle);
        AvrcpBipRspCoverArtAttributes artAttributes = mCoverArtAttributesMap.get(imgHandle);
        /* Check if we have the image handle in map, if not return null */
        if (artAttributes == null) {
            Log.w(TAG, "encode: imageHandle =" +  imgHandle + " is not in hashmap");
            return null;
        }
        StringWriter sw = new StringWriter();
        XmlSerializer xmlMsgElement = new FastXmlSerializer();
        // construct the XML tag for a single msg in the msglisting
        try {
            xmlMsgElement.setOutput(sw);
            xmlMsgElement.startDocument("UTF-8", true);
            xmlMsgElement.setFeature("http://xmlpull.org/v1/doc/features.html#indent-output", true);
            xmlMsgElement.startTag(null, "image-properties");
            xmlMsgElement.attribute(null, "version", artAttributes.getVersion());
            xmlMsgElement.attribute(null, "handle", imgHandle);
            xmlMsgElement.startTag(null, "native");
            xmlMsgElement.attribute(null, "encoding", artAttributes.getNativeEncoding());
            xmlMsgElement.attribute(null, "pixel", artAttributes.getNativePixel());
            xmlMsgElement.attribute(null, "size", artAttributes.getNativeSize());
            xmlMsgElement.endTag(null, "native");
            xmlMsgElement.startTag(null, "variant");
            xmlMsgElement.attribute(null, "encoding", "JPEG");
            xmlMsgElement.attribute(null, "pixel", MIN_SUPPORTED_WIDTH + "*" + MIN_SUPPORTED_WIDTH +
                "-" + MAX_SUPPORTED_WIDTH + "*" + MAX_SUPPORTED_HEIGHT);
            xmlMsgElement.endTag(null, "variant");
            xmlMsgElement.startTag(null, "variant");
            xmlMsgElement.attribute(null, "encoding", "PNG");
            xmlMsgElement.attribute(null, "pixel", MIN_SUPPORTED_WIDTH + "*" + MIN_SUPPORTED_WIDTH +
                "-" + MAX_SUPPORTED_WIDTH + "*" + MAX_SUPPORTED_HEIGHT);
            xmlMsgElement.endTag(null, "variant");
            xmlMsgElement.endTag(null, "image-properties");
            xmlMsgElement.endDocument();
        } catch (IllegalArgumentException | IllegalStateException | IOException e) {
            Log.w(TAG, "encode :" + e);
        }
        if (V) Log.v(TAG, "Image properties XML = " + sw.toString());
        return sw.toString().getBytes("UTF-8");
    }

    private synchronized void delTmpFile() {
        if (TextUtils.isEmpty(mTmpFilePath)) {
            Log.e(TAG," delTmpFile storage not mounted yet");
            return;
        }
        File f = new File(mTmpFilePath);
        boolean isDeleted = false;
        if (f != null && f.isFile() && f.exists()) {
            // delete the file now, may be due to battery removal or some exception
            isDeleted = f.delete();
        }
        if (V) Log.v(TAG," delTmpFile :" + f.getAbsolutePath() + " isDeleted :" + isDeleted);
    }

    private void setTmpPath(String tag) {
        if (Environment.MEDIA_MOUNTED.equals(Environment.getExternalStorageState())) {
            mTmpFilePath = mContext.getExternalFilesDir(null).getAbsolutePath()
                + "/tmpBtBip" + tag.replaceAll(":","") + ".jpg";
            if (V) Log.v(TAG," mTmpFilePath :" + mTmpFilePath);
        } else {
            Log.e(TAG, "getStorageDirectory state=" + Environment.getExternalStorageState());
        }
    }

    private boolean isAlbumArtPresent(long albumId){
        boolean albumFound = false;
        Uri albumUri = ContentUris.withAppendedId(URI_ALBUM, albumId);
        Bitmap bitmap = null;
        try {
            bitmap = mContext.getContentResolver().loadThumbnail(albumUri,
                    new Size(MIN_SUPPORTED_HEIGHT, MIN_SUPPORTED_HEIGHT), null);
        } catch (IOException e) {
            if (V) {
                e.printStackTrace();
            } else {
                if (D) Log.d(TAG," "+e.toString());
            }
        }
        if (bitmap != null) {
            albumFound = true;
            bitmap.recycle();
            bitmap = null;
        }
        if (V) Log.v(TAG, " isAlbumArtPresent " + albumFound);
        return  albumFound;
    }

    private boolean isStorageReady() {
        if (TextUtils.isEmpty(mTmpFilePath)) {
            setTmpPath(tag);
            delTmpFile();
        }
        return !TextUtils.isEmpty(mTmpFilePath);
    }
}
