package com.example.myocr.drugentry

import android.net.Uri
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView

/**
 * 已拍照片缩略图适配器
 *
 * 在确认页展示用户拍摄的药品包装照片。
 */
class PhotoThumbnailAdapter(
    private val photos: List<Uri>
) : RecyclerView.Adapter<PhotoThumbnailAdapter.PhotoViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PhotoViewHolder {
        val imageView = ImageView(parent.context).apply {
            layoutParams = ViewGroup.LayoutParams(80.dpToPx(parent.context), 80.dpToPx(parent.context))
            scaleType = ImageView.ScaleType.CENTER_CROP
            setPadding(4, 4, 4, 4)
        }
        return PhotoViewHolder(imageView)
    }

    override fun onBindViewHolder(holder: PhotoViewHolder, position: Int) {
        val uri = photos[position]
        holder.imageView.setImageURI(uri)
    }

    override fun getItemCount() = photos.size

    class PhotoViewHolder(val imageView: ImageView) : RecyclerView.ViewHolder(imageView)

    private fun Int.dpToPx(context: android.content.Context): Int {
        return (this * context.resources.displayMetrics.density).toInt()
    }
}
