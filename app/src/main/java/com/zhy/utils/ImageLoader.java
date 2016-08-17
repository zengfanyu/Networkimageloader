package com.zhy.utils;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.support.v4.util.LruCache;
import android.util.Log;
import android.widget.ImageView;

import com.zhy.utils.ImageSizeUtil.ImageSize;

import java.io.File;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;

/**
 * 图片加载类 框架
 *
 * @author zhy
 */
public class ImageLoader {
    private static ImageLoader mInstance;

    /**
     * 图片缓存的核心对象
     */
    private LruCache<String, Bitmap> mLruCache;
    /**
     * 线程池
     */
    private ExecutorService mThreadPool;
    private static final int DEAFULT_THREAD_COUNT = 1;
    /**
     * 队列的调度方式
     */
    private Type mType = Type.LIFO;
    /**
     * 任务队列
     */
    private LinkedList<Runnable> mTaskQueue;
    /**
     * 后台轮询线程
     */
    private Thread mPoolThread;
    private Handler mPoolThreadHandler;
    /**
     * UI线程中的Handler
     */
    private Handler mUIHandler;

    private Semaphore mSemaphorePoolThreadHandler = new Semaphore(0);

    /*
    *  Semaphore当前在多线程环境下被扩放使用，操作系统的信号量是个很重要的概念，
    *  在进程控制方面都有应用。Java 并发库 的Semaphore 可以很轻松完成信号量控制，
    *  Semaphore可以控制某个资源可被同时访问的个数，通过 acquire() 获取一个许可，
    *  如果没有就等待，而 release() 释放一个许可。
    *
    *
    * */
    private Semaphore mSemaphoreThreadPool;

    private boolean isDiskCacheEnable = true;

    private static final String TAG = "ImageLoader";

    public enum Type {
        FIFO, LIFO;
    }

    private ImageLoader(int threadCount, Type type) {
        init(threadCount, type);
    }

    /**
     * 初始化
     *
     * @param threadCount
     * @param type
     */
    private void init(int threadCount, Type type) {
        initBackThread();

        // 获取我们应用的最大可用内存
        int maxMemory = (int) Runtime.getRuntime().maxMemory();
        int cacheMemory = maxMemory / 8;
        mLruCache = new LruCache<String, Bitmap>(cacheMemory) {
            @Override
            protected int sizeOf(String key, Bitmap value) {
//                value.getByteCount(); 等价.下面的方法处理了兼容性的问题

                return value.getRowBytes() * value.getHeight();
            }

        };

        // 创建线程池
        mThreadPool = Executors.newFixedThreadPool(threadCount);
        mTaskQueue = new LinkedList<Runnable>();
        mType = type;
        mSemaphoreThreadPool = new Semaphore(threadCount);
    }

    /**
     * 初始化后台轮询线程
     */
    private void initBackThread() {
        // 后台轮询线程
        mPoolThread = new Thread() {
            @Override
            public void run() {
                Looper.prepare();
                mPoolThreadHandler = new Handler() {
                    @Override
                    public void handleMessage(Message msg) {
                        // 线程池去取出一个任务进行执行
                        mThreadPool.execute(getTask());
                        try {
                            mSemaphoreThreadPool.acquire();
                        } catch (InterruptedException e) {
                        }
                    }
                };
                // 释放一个信号量
                mSemaphorePoolThreadHandler.release();
                Looper.loop();

                /*
                * loop()方法的作用
                * 1.返回在prepare()中存储的Looper实例
                * 2.进入死循环,在死循环中干的事情:
                *   <1>从MessageQueue中取出消息,如果没有消息,则阻塞式等待
                *   <2>调用Handler.handleMessage()方法,处理消息
                *   <3>释放资源
                *
                * */
            }


        };

        mPoolThread.start();
    }

    /**
     * 默认构造方法,获取单例的对象
     *
     * @author zfy
     * @created at 2016/8/16 14:33
     */
    public static ImageLoader getInstance() {
        if (mInstance == null) {    //考虑线程安全问题
            synchronized (ImageLoader.class) {
                if (mInstance == null) {
                    mInstance = new ImageLoader(DEAFULT_THREAD_COUNT, Type.LIFO);
                }
            }
        }
        return mInstance;
    }

    /**
     * 带参数的构造方法
     *
     * @param threadCount 后台加载图片线程数量
     * @param type        从任务队列中取任务的策略
     * @return mInstance
     * @author zfy
     * @created at 2016/8/16 16:26
     */
    public static ImageLoader getInstance(int threadCount, Type type) {
        if (mInstance == null) {
            synchronized (ImageLoader.class) {
                if (mInstance == null) {
                    mInstance = new ImageLoader(threadCount, type);
                }
            }
        }
        return mInstance;
    }

    /**
     * 根据path为imageview设置图片 , 异步的处理方法
     *
     * @param path
     * @param imageView
     */
    public void loadImage(final String path, final ImageView imageView,
                          final boolean isFromNet) {
        imageView.setTag(path);
        if (mUIHandler == null) {
            mUIHandler = new Handler() {
                public void handleMessage(Message msg) {
                    // 获取得到图片，为imageview回调设置图片
                    ImgBeanHolder holder = (ImgBeanHolder) msg.obj;
                    Bitmap bm = holder.bitmap;
                    ImageView imageview = holder.imageView;
                    String path = holder.path;
                    // 将path与getTag存储路径进行比较 防止错位
                    if (imageview.getTag().toString().equals(path)) {
                        imageview.setImageBitmap(bm);
                    }
                }


            };
        }

        // 根据path在缓存中获取bitmap
        Bitmap bm = getBitmapFromLruCache(path);

        if (bm != null) {
            refreashBitmap(path, imageView, bm);
        } else {
            addTask(buildTask(path, imageView, isFromNet));
        }

    }

    /**
     * 根据传入的参数，新建一个任务 ,同步
     *
     * @param path
     * @param imageView
     * @param isFromNet
     * @return
     */
    private Runnable buildTask(final String path, final ImageView imageView,
                               final boolean isFromNet) {
        return new Runnable() {
            @Override
            public void run() {
                Bitmap bm = null;
                if (isFromNet) {
                    File file = getDiskCacheDir(imageView.getContext(),
                            md5(path));
                    if (file.exists())// 如果在缓存文件中发现
                    {
                        Log.e(TAG, "find image :" + path + " in disk cache .");
                        bm = loadImageFromLocal(file.getAbsolutePath(),
                                imageView);
                    } else {
                        if (isDiskCacheEnable)// 检测是否开启硬盘缓存
                        {
                            boolean downloadState = DownloadImgUtils
                                    .downloadImgByUrl(path, file);
                            if (downloadState)// 如果下载成功
                            {
                                Log.e(TAG,
                                        "download image :" + path
                                                + " to disk cache . path is "
                                                + file.getAbsolutePath());
                                bm = loadImageFromLocal(file.getAbsolutePath(),
                                        imageView);
                            }
                        } else
                        // 直接从网络加载
                        {
                            Log.e(TAG, "load image :" + path + " to memory.");
                            bm = DownloadImgUtils.downloadImgByUrl(path,
                                    imageView);
                        }
                    }
                } else {
                    bm = loadImageFromLocal(path, imageView);
                }
                // 3、把图片加入到缓存
                addBitmapToLruCache(path, bm);
                refreashBitmap(path, imageView, bm);
                mSemaphoreThreadPool.release();
            }


        };
    }

    /**
     * 根据策略,从任务队列中取出一个任务的方法
     *
     * @return runnable
     * @author zfy
     * @created at 2016/8/16 16:39
     */
    private Runnable getTask() {
        if (mType == Type.FIFO) {
            return mTaskQueue.removeFirst();
        } else if (mType == Type.LIFO) {
            return mTaskQueue.removeLast();
        }
        return null;
    }

    /**
     * 将获取到的,处理过后的,图片发送到主线程的Handler 异步
     *
     * @param path      路径
     * @param imageView
     * @param bm
     * @return void
     * @author zfy
     * @created at 2016/8/16 16:37
     */
    private void refreashBitmap(final String path, final ImageView imageView,
                                Bitmap bm) {
        Message message = Message.obtain();
        ImgBeanHolder holder = new ImgBeanHolder();
        holder.bitmap = bm;
        holder.path = path;
        holder.imageView = imageView;
        message.obj = holder;
        mUIHandler.sendMessage(message);
    }

    /**
     * 从本地文件中获取图片的方法
     *
     * @param path
     * @param imageView
     * @return bm
     * @author zfy
     * @created at 2016/8/16 16:24
     */
    private Bitmap loadImageFromLocal(final String path, final ImageView imageView) {
        Bitmap bm;
        // 加载图片
        // 图片的压缩
        // 1、获得图片需要显示的大小
        ImageSize imageSize = ImageSizeUtil.getImageViewSize(imageView);
        // 2、压缩图片
        bm = decodeSampledBitmapFromPath(path, imageSize.width,
                imageSize.height);
        return bm;
    }

    /**
     * 和MD5相关的,忽略不计
     *
     * @param bytes
     * @return
     */
    public String bytes2hex02(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        String tmp = null;
        for (byte b : bytes) {
            // 将每个字节与0xFF进行与运算，然后转化为10进制，然后借助于Integer再转化为16进制
            tmp = Integer.toHexString(0xFF & b);
            if (tmp.length() == 1)// 每个字节8为，转为16进制标志，2个16进制位
            {
                tmp = "0" + tmp;
            }
            sb.append(tmp);
        }

        return sb.toString();

    }

    /**
     * 利用签名辅助类，将字符串字节数组
     *
     * @param str
     * @return
     */
    public String md5(String str) {
        byte[] digest = null;
        try {
            MessageDigest md = MessageDigest.getInstance("md5");
            digest = md.digest(str.getBytes());
            return bytes2hex02(digest);

        } catch (NoSuchAlgorithmException e) {
            e.printStackTrace();
        }
        return null;
    }

    /**
     * 根据图片需要显示的宽和高对图片进行压缩
     *
     * @param path
     * @param width
     * @param height
     * @return
     */
    protected Bitmap decodeSampledBitmapFromPath(String path, int width,
                                                 int height) {
        // 获得图片的宽和高，并不把图片加载到内存中
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(path, options);

        options.inSampleSize = ImageSizeUtil.caculateInSampleSize(options,
                width, height);

        // 使用获得到的InSampleSize再次解析图片
        options.inJustDecodeBounds = false;
        Bitmap bitmap = BitmapFactory.decodeFile(path, options);
        return bitmap;
    }

    private synchronized void addTask(Runnable runnable) {
        mTaskQueue.add(runnable);
        // if(mPoolThreadHandler==null)wait();
        try {
            if (mPoolThreadHandler == null)
                mSemaphorePoolThreadHandler.acquire();
        } catch (InterruptedException e) {
        }
        mPoolThreadHandler.sendEmptyMessage(0x110);
    }

    /**
     * 获得缓存图片的地址
     * <p/>
     * 有SD卡就返回SD卡地址
     * 无SD卡放返回应用本身的缓存文件夹地址
     *
     * @param context
     * @param uniqueName
     * @return
     */
    public File getDiskCacheDir(Context context, String uniqueName) {
        String cachePath;
        if (Environment.MEDIA_MOUNTED.equals(Environment
                .getExternalStorageState())) {
            cachePath = context.getExternalCacheDir().getPath();
        } else {
            cachePath = context.getCacheDir().getPath();
        }
        return new File(cachePath + File.separator + uniqueName);
    }

    /**
     * 将图片加入LruCache
     *
     * @param path
     * @param bm
     */
    protected void addBitmapToLruCache(String path, Bitmap bm) {
        if (getBitmapFromLruCache(path) == null) {
            if (bm != null)
                mLruCache.put(path, bm);
        }
    }

    /**
     * 根据path在LruCache中获取bitmap
     *
     * @param key
     * @return
     */
    private Bitmap getBitmapFromLruCache(String key) {

        Log.d("getBitmapFromLruCache",key);

        return mLruCache.get(key);

    }

    private class ImgBeanHolder {
        Bitmap bitmap;
        ImageView imageView;
        String path;
    }
}
