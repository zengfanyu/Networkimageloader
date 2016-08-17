##Networkimageloader##

- **一.设计初衷**
	<p>在开发项目过程中,经常会用到许多优秀的开源图片加载框架,比如:Universal-Image-Loader,Picasso,Fresco,xUtils的BitmapUtils模块.这些开源框架大大的提高了我们的开发效率.
	<p>在项目中经常会碰到这种需求:只是要**流畅的**从网络加载图片,做好缓存,然后显示,在这种情况下,如果采用上述优秀的框架,当然是没有问题的.但是相对于整个项目来说,就显得太重了.有种"杀鸡用牛刀"的感觉.
	<p> 基于这一需求,本人研究了部分开源框架的源码,查询相关资料之后,自己打造了一个轻量级的ImageLoader---Networkimageloader.
	<p>这也算是本人自己造的第一个"小玩具轮子"了吧.

- **二.主要实现功能**
	
		1. 同步加载
		2. 异步加载
		3. 图片压缩
		4. 内存缓存
		5. 磁盘缓存
		6. 网络拉取

	最终实现的效果为:**不崩(OOM),不卡,不错位,不慢**.

- **三.功能模块**<p>
	3.1 **ImageSizeUtils**

	 		拿到ImageView的实际宽高
			计算出inSampleSize

	3.2 **DownLoadImgUtils**

			开启本地缓存的情况下,从网络拉取数据,到本地
			没开启本地换地的情况下,直接从网络拉取数据,返回Bitmap

	3.3 **ImageLoader(核心类)**

		采用单例模式,包含一个LruCache管理图片
		采用TaskQueue,将每一个加载图片的请求封装成Task存入TaskQueue
		采用后台轮训线程,处理请求
		可设置调度策略:先进先出,后进先出

- **四.参考资料(感谢)**

	1. [Android官方培训课程中文版(v0.9.5)第四章](http://hukai.me/android-training-course-in-chinese/)
	2. Android开发艺术探索--任玉刚.第12章,Bitmap的加载和Cache
