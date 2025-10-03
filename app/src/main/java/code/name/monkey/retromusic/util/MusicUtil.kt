package code.name.monkey.retromusic.util

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import android.provider.BaseColumns
import android.provider.MediaStore
import android.util.Log
import android.os.Handler
import android.os.Looper
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import androidx.fragment.app.FragmentActivity
import code.name.monkey.appthemehelper.util.VersionUtils
import code.name.monkey.retromusic.Constants
import code.name.monkey.retromusic.R
import code.name.monkey.retromusic.db.PlaylistEntity
import code.name.monkey.retromusic.db.SongEntity
import code.name.monkey.retromusic.db.toSongEntity
import code.name.monkey.retromusic.extensions.getLong
import code.name.monkey.retromusic.extensions.showToast
import code.name.monkey.retromusic.helper.MusicPlayerRemote.removeFromQueue
import code.name.monkey.retromusic.model.Artist
import code.name.monkey.retromusic.model.Song
import code.name.monkey.retromusic.model.lyrics.AbsSynchronizedLyrics
import code.name.monkey.retromusic.repository.Repository
import code.name.monkey.retromusic.repository.SongRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.withContext
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*
import java.util.regex.Pattern


object MusicUtil : KoinComponent {
    fun createShareSongFileIntent(context: Context, song: Song): Intent {
        return Intent().apply {
            action = Intent.ACTION_SEND
            putExtra(
                Intent.EXTRA_STREAM, try {
                    FileProvider.getUriForFile(
                        context,
                        context.applicationContext.packageName,
                        File(song.data)
                    )
                } catch (e: IllegalArgumentException) {
                    getSongFileUri(song.id)
                }
            )
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            type = "audio/*"
        }
    }

    fun createShareMultipleSongIntent(context: Context, songs: List<Song>): Intent {
        return Intent().apply {
            action = Intent.ACTION_SEND_MULTIPLE
            type = "audio/*"

            val files = ArrayList<Uri>()

            for (song in songs) {
                files.add(
                    try {
                        FileProvider.getUriForFile(
                            context,
                            context.applicationContext.packageName,
                            File(song.data)
                        )
                    } catch (e: IllegalArgumentException) {
                        getSongFileUri(song.id)
                    }
                )
            }
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, files)
        }
    }

    fun buildInfoString(string1: String?, string2: String?): String {
        if (string1.isNullOrEmpty()) {
            return if (string2.isNullOrEmpty()) "" else string2
        }
        return if (string2.isNullOrEmpty()) if (string1.isNullOrEmpty()) "" else string1 else "$string1  •  $string2"
    }

    fun createAlbumArtFile(context: Context): File {
        return File(
            createAlbumArtDir(context),
            System.currentTimeMillis().toString()
        )
    }

    private fun createAlbumArtDir(context: Context): File {
        val albumArtDir = File(
            if (VersionUtils.hasR()) context.cacheDir else getExternalStorageDirectory(),
            "/albumthumbs/"
        )
        if (!albumArtDir.exists()) {
            albumArtDir.mkdirs()
            try {
                File(albumArtDir, ".nomedia").createNewFile()
            } catch (e: IOException) {
                e.printStackTrace()
            }
        }
        return albumArtDir
    }

    fun deleteAlbumArt(context: Context, albumId: Long) {
        val contentResolver = context.contentResolver
        val localUri = "content://media/external/audio/albumart".toUri()
        try {
            // First, if we previously tracked a created URI or id for this album, try to delete it
            val tracked = PreferenceUtil.getAlbumArtUri(albumId)
            if (!tracked.isNullOrEmpty()) {
                try {
                    when {
                        tracked.startsWith("ms:") -> {
                            // Stored MediaStore image id
                            val idPart = tracked.removePrefix("ms:")
                            val imageId = idPart.toLongOrNull()
                            if (imageId != null) {
                                try {
                                    val imagesUri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, imageId)
                                    context.contentResolver.delete(imagesUri, null, null)
                                } catch (e: Exception) {
                                    Log.w("MusicUtil", "Failed to delete tracked MediaStore image id: $imageId", e)
                                }
                            }
                        }
                        tracked.startsWith("saf:") -> {
                            // Stored SAF document uri
                            val uriString = tracked.removePrefix("saf:")
                            try {
                                val trackedUri = Uri.parse(uriString)
                                // If it's a document uri, use DocumentFile to delete
                                if (trackedUri.scheme == "content") {
                                    try {
                                        DocumentFile.fromSingleUri(context, trackedUri)?.delete()
                                    } catch (e: Exception) {
                                        // fallback to contentResolver
                                        try {
                                            context.contentResolver.delete(trackedUri, null, null)
                                        } catch (ex: Exception) {
                                            Log.w("MusicUtil", "Failed to delete tracked SAF album art uri", ex)
                                        }
                                    }
                                } else {
                                    try {
                                        val f = File(trackedUri.path ?: "")
                                        if (f.exists()) f.delete()
                                    } catch (e: Exception) {
                                        Log.w("MusicUtil", "Failed to delete tracked SAF album art file", e)
                                    }
                                }
                            } catch (e: Exception) {
                                Log.w("MusicUtil", "Error parsing tracked SAF uri", e)
                            }
                        }
                        else -> {
                            // legacy stored raw uri/path - try deleting directly
                            try {
                                val trackedUri = Uri.parse(tracked)
                                if (trackedUri.scheme == "content") {
                                    try {
                                        DocumentFile.fromSingleUri(context, trackedUri)?.delete()
                                    } catch (e: Exception) {
                                        try {
                                            context.contentResolver.delete(trackedUri, null, null)
                                        } catch (ex: Exception) {
                                            Log.w("MusicUtil", "Failed to delete tracked album art uri", ex)
                                        }
                                    }
                                } else {
                                    val f = File(trackedUri.path ?: "")
                                    if (f.exists()) f.delete()
                                }
                            } catch (e: Exception) {
                                Log.w("MusicUtil", "Error deleting tracked album art", e)
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w("MusicUtil", "Error deleting tracked album art", e)
                } finally {
                    PreferenceUtil.setAlbumArtUri(albumId, null)
                }
            }

            // Try to query the existing albumart entry to remove underlying file if any
            val queryUri = ContentUris.withAppendedId(localUri, albumId)
            context.contentResolver.query(queryUri, arrayOf(Constants.DATA), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val path = cursor.getString(0)
                        if (!path.isNullOrEmpty()) {
                            try {
                                val file = File(path)
                                if (file.exists()) {
                                    file.delete()
                                }
                            } catch (e: Exception) {
                                Log.w("MusicUtil", "Could not delete album art file: $path", e)
                            }
                        }
                    }
                }

            contentResolver.delete(ContentUris.withAppendedId(localUri, albumId), null, null)
        } catch (e: Exception) {
            Log.w("MusicUtil", "Failed to delete album art entry for album $albumId", e)
        } finally {
            contentResolver.notifyChange(localUri, null)
        }
    }

    fun getArtistInfoString(
        context: Context,
        artist: Artist,
    ): String {
        val albumCount = artist.albumCount
        val songCount = artist.songCount
        val albumString =
            if (albumCount == 1) context.resources.getString(R.string.album)
            else context.resources.getString(R.string.albums)
        val songString =
            if (songCount == 1) context.resources.getString(R.string.song)
            else context.resources.getString(R.string.songs)
        return "$albumCount $albumString • $songCount $songString"
    }

    //iTunes uses for example 1002 for track 2 CD1 or 3011 for track 11 CD3.
    //this method converts those values to normal tracknumbers
    fun getFixedTrackNumber(trackNumberToFix: Int): Int {
        return trackNumberToFix % 1000
    }

    fun getLyrics(song: Song): String? {
        var lyrics: String? = "No lyrics found"
        val file = File(song.data)
        try {
            lyrics = AudioFileIO.read(file).tagOrCreateDefault.getFirst(FieldKey.LYRICS)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        if (lyrics == null || lyrics.trim { it <= ' ' }.isEmpty() || AbsSynchronizedLyrics
                .isSynchronized(lyrics)
        ) {
            val dir = file.absoluteFile.parentFile
            if (dir != null && dir.exists() && dir.isDirectory) {
                val format = ".*%s.*\\.(lrc|txt)"
                val filename = Pattern.quote(
                    FileUtil.stripExtension(file.name)
                )
                val songtitle = Pattern.quote(song.title)
                val patterns =
                    ArrayList<Pattern>()
                patterns.add(
                    Pattern.compile(
                        String.format(format, filename),
                        Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE
                    )
                )
                patterns.add(
                    Pattern.compile(
                        String.format(format, songtitle),
                        Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE
                    )
                )
                val files =
                    dir.listFiles { f: File ->
                        for (pattern in patterns) {
                            if (pattern.matcher(f.name).matches()) {
                                return@listFiles true
                            }
                        }
                        false
                    }
                if (files != null && files.isNotEmpty()) {
                    for (f in files) {
                        try {
                            val newLyrics =
                                FileUtil.read(f)
                            if (newLyrics != null && newLyrics.trim { it <= ' ' }.isNotEmpty()) {
                                if (AbsSynchronizedLyrics.isSynchronized(newLyrics)) {
                                    return newLyrics
                                }
                                lyrics = newLyrics
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                }
            }
        }
        return lyrics
    }

    @JvmStatic
    fun getMediaStoreAlbumCoverUri(albumId: Long): Uri {
        val sArtworkUri = "content://media/external/audio/albumart".toUri()
        return ContentUris.withAppendedId(sArtworkUri, albumId)
    }


    fun getPlaylistInfoString(
        context: Context,
        songs: List<Song>,
    ): String {
        val duration = getTotalDuration(songs)
        return buildInfoString(
            getSongCountString(context, songs.size),
            getReadableDurationString(duration)
        )
    }

    fun playlistInfoString(
        context: Context,
        songs: List<SongEntity>,
    ): String {
        return getSongCountString(context, songs.size)
    }

    fun getReadableDurationString(songDurationMillis: Long): String {
        var minutes = songDurationMillis / 1000 / 60
        val seconds = songDurationMillis / 1000 % 60
        return if (minutes < 60) {
            String.format(
                Locale.getDefault(),
                "%02d:%02d",
                minutes,
                seconds
            )
        } else {
            val hours = minutes / 60
            minutes %= 60
            String.format(
                Locale.getDefault(),
                "%02d:%02d:%02d",
                hours,
                minutes,
                seconds
            )
        }
    }

    fun getSectionName(mediaTitle: String?, stripPrefix: Boolean = false): String {
        var musicMediaTitle = mediaTitle
        return try {
            if (musicMediaTitle.isNullOrEmpty()) {
                return "-"
            }
            musicMediaTitle = musicMediaTitle.trim { it <= ' ' }.lowercase()
            if (stripPrefix) {
                if (musicMediaTitle.startsWith("the ")) {
                    musicMediaTitle = musicMediaTitle.substring(4)
                } else if (musicMediaTitle.startsWith("a ")) {
                    musicMediaTitle = musicMediaTitle.substring(2)
                }
            }

            if (musicMediaTitle.isEmpty()) {
                ""
            } else musicMediaTitle.substring(0, 1).uppercase()
        } catch (e: Exception) {
            ""
        }
    }

    fun getSongCountString(context: Context, songCount: Int): String {
        val songString = if (songCount == 1) context.resources
            .getString(R.string.song) else context.resources.getString(R.string.songs)
        return "$songCount $songString"
    }

    fun getSongFileUri(songId: Long): Uri {
        return ContentUris.withAppendedId(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            songId
        )
    }

    fun getSongFilePath(context: Context, uri: Uri): String {
        val projection = arrayOf(Constants.DATA)
        context.contentResolver.query(uri, projection, null, null, null)?.use {
            if (it.moveToFirst()) {
                return it.getString(0)
            }
        }
        return ""
    }

    fun getTotalDuration(songs: List<Song>): Long {
        var duration: Long = 0
        for (i in songs.indices) {
            duration += songs[i].duration
        }
        return duration
    }

    fun getYearString(year: Int): String {
        return if (year > 0) year.toString() else "-"
    }

    fun indexOfSongInList(songs: List<Song>, songId: Long): Int {
        return songs.indexOfFirst { it.id == songId }
    }

    fun getDateModifiedString(date: Long): String {
        val calendar: Calendar = Calendar.getInstance()
        val pattern = "dd/MM/yyyy hh:mm:ss"
        calendar.timeInMillis = date
        val formatter = SimpleDateFormat(pattern, Locale.ENGLISH)
        return formatter.format(calendar.time)
    }

    fun insertAlbumArt(
        context: Context,
        albumId: Long,
        path: String?
    ) {
        val contentResolver = context.contentResolver
        val artworkUri = "content://media/external/audio/albumart".toUri()
        // Remove any existing album art entry
        try {
            contentResolver.delete(ContentUris.withAppendedId(artworkUri, albumId), null, null)
        } catch (e: Exception) {
            Log.w("MusicUtil", "Could not delete existing album art entry", e)
        }

        if (path.isNullOrEmpty()) {
            // nothing to insert
            contentResolver.notifyChange(artworkUri, null)
            return
        }

        // On Android R+ we should insert the artwork into MediaStore in a way other apps can access.
        // Try to write a media store entry pointing to the file path. If that fails, fall back to legacy insert.
        try {
            if (VersionUtils.hasR()) {
                // Try to add as an image in MediaStore and then point albumart to that file path
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, File(path).name)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/AlbumArt")
                }

                var imageUri = try {
                    contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                } catch (e: Exception) {
                    Log.w("MusicUtil", "Failed to insert into Images MediaStore", e)
                    null
                }

                // If we failed to insert into MediaStore, try SAF fallback if user provided a SAF tree URI
                if (imageUri == null) {
                    val safTree = PreferenceUtil.safSdCardUri
                    if (!safTree.isNullOrEmpty()) {
                        try {
                            val treeUri = Uri.parse(safTree)
                            val docId = DocumentFile.fromTreeUri(context, treeUri)?.createFile("image/jpeg", File(path).name)?.uri
                            if (docId != null) {
                                // write file contents
                                context.contentResolver.openOutputStream(docId)?.use { out ->
                                    File(path).inputStream().use { input ->
                                        input.copyTo(out)
                                    }
                                }
                                imageUri = docId
                                // persist tracking so we can delete it later
                                PreferenceUtil.setAlbumArtUri(albumId, imageUri.toString())
                            }
                        } catch (e: Exception) {
                            Log.w("MusicUtil", "SAF fallback failed", e)
                        }
                    }
                }

                if (imageUri != null) {
                    // Copy file contents into the MediaStore uri
                    try {
                        context.contentResolver.openOutputStream(imageUri)?.use { out ->
                            File(path).inputStream().use { input ->
                                input.copyTo(out)
                            }
                        }

                        // After writing the image, try to insert albumart entry pointing to this file
                        val dataPath = try {
                            getSongFilePath(context, imageUri)
                        } catch (e: Exception) {
                            // If we used SAF the path may not be resolvable; store the uri in preferences and use SAF later for delete
                            null
                        }

                        val albumValues = ContentValues().apply {
                            put("album_id", albumId)
                            put("_data", dataPath)
                        }
                        contentResolver.insert(artworkUri, albumValues)
                        contentResolver.notifyChange(artworkUri, null)

                        // Track created media uri for deletion later. If this was a MediaStore insert
                        // we can store the image id as ms:<id>, otherwise saf:<uri>
                        try {
                            val id = ContentUris.parseId(imageUri)
                            PreferenceUtil.setAlbumArtUri(albumId, "ms:" + id)
                        } catch (e: Exception) {
                            // Not a MediaStore id, store SAF/document uri
                            try {
                                PreferenceUtil.setAlbumArtUri(albumId, "saf:" + imageUri.toString())
                            } catch (ex: Exception) {
                                Log.w("MusicUtil", "Failed to persist tracked album art uri", ex)
                            }
                        }

                        // Notify success to user
                        try {
                            Handler(Looper.getMainLooper()).post {
                                context.showToast("Album art updated")
                            }
                        } catch (e: Exception) {
                        }

                        return
                    } catch (e: Exception) {
                        Log.w("MusicUtil", "Failed to write image to MediaStore uri", e)
                    }
                }
                // If inserting into Images MediaStore failed, fallthrough to legacy insertion below
            }

            // Legacy / fallback insertion into albumart content provider
            val values = ContentValues().apply {
                put("album_id", albumId)
                put("_data", path)
            }
            try {
                contentResolver.insert(artworkUri, values)
                contentResolver.notifyChange(artworkUri, null)
            } catch (e: IllegalArgumentException) {
                Log.e("MusicUtil", "Failed to insert album art", e)
            }
        } catch (e: Exception) {
            Log.e("MusicUtil", "Failed to insert album art (unexpected)", e)
        }
    }

    fun isArtistNameUnknown(artistName: String?): Boolean {
        if (artistName.isNullOrEmpty()) {
            return false
        }
        if (artistName == Artist.UNKNOWN_ARTIST_DISPLAY_NAME) {
            return true
        }
        val tempName = artistName.trim { it <= ' ' }.lowercase()
        return tempName == "unknown" || tempName == "<unknown>"
    }

    fun isVariousArtists(artistName: String?): Boolean {
        if (artistName.isNullOrEmpty()) {
            return false
        }
        if (artistName == Artist.VARIOUS_ARTISTS_DISPLAY_NAME) {
            return true
        }
        return false
    }

    private val repository = get<Repository>()
    suspend fun toggleFavorite(song: Song) {
        withContext(IO) {
            val playlist: PlaylistEntity = repository.favoritePlaylist()
            val songEntity = song.toSongEntity(playlist.playListId)
            val isFavorite = repository.isFavoriteSong(songEntity).isNotEmpty()
            if (isFavorite) {
                repository.removeSongFromPlaylist(songEntity)
            } else {
                repository.insertSongs(listOf(song.toSongEntity(playlist.playListId)))
            }
        }
    }

    suspend fun isFavorite(song: Song) = repository.isSongFavorite(song.id)

    fun deleteTracks(
        activity: FragmentActivity,
        songs: List<Song>,
        safUris: List<Uri>?,
        callback: Runnable?,
    ) {
        val songRepository: SongRepository = get()
        val projection = arrayOf(
            BaseColumns._ID, Constants.DATA
        )
        // Split the query into multiple batches, and merge the resulting cursors
        var batchStart: Int
        var batchEnd = 0
        val batchSize =
            1000000 / 10 // 10^6 being the SQLite limite on the query lenth in bytes, 10 being the max number of digits in an int, used to store the track ID
        val songCount = songs.size

        while (batchEnd < songCount) {
            batchStart = batchEnd

            val selection = StringBuilder()
            selection.append(BaseColumns._ID + " IN (")

            var i = 0
            while (i < batchSize - 1 && batchEnd < songCount - 1) {
                selection.append(songs[batchEnd].id)
                selection.append(",")
                i++
                batchEnd++
            }
            // The last element of a batch
            // The last element of a batch
            selection.append(songs[batchEnd].id)
            batchEnd++
            selection.append(")")

            try {
                val cursor = activity.contentResolver.query(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, projection, selection.toString(),
                    null, null
                )
                if (cursor != null) {
                    // Step 1: Remove selected tracks from the current playlist, as well
                    // as from the album art cache
                    cursor.moveToFirst()
                    while (!cursor.isAfterLast) {
                        val id = cursor.getLong(BaseColumns._ID)
                        val song: Song = songRepository.song(id)
                        removeFromQueue(song)
                        cursor.moveToNext()
                    }

                    // Step 2: Remove selected tracks from the database
                    activity.contentResolver.delete(
                        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                        selection.toString(), null
                    )
                    // Step 3: Remove files from card
                    cursor.moveToFirst()
                    var index = batchStart
                    while (!cursor.isAfterLast) {
                        val name = cursor.getString(1)
                        val safUri =
                            if (safUris == null || safUris.size <= index) null else safUris[index]
                        SAFUtil.delete(activity, name, safUri)
                        index++
                        cursor.moveToNext()
                    }
                    cursor.close()
                }
            } catch (ignored: SecurityException) {

            }
            activity.contentResolver.notifyChange("content://media".toUri(), null)
            activity.runOnUiThread {
                activity.showToast(activity.getString(R.string.deleted_x_songs, songCount))
                callback?.run()
            }
        }
    }

    suspend fun deleteTracks(context: Context, songs: List<Song>) {
        val projection = arrayOf(BaseColumns._ID, Constants.DATA)
        val selection = StringBuilder()
        selection.append(BaseColumns._ID + " IN (")
        for (i in songs.indices) {
            selection.append(songs[i].id)
            if (i < songs.size - 1) {
                selection.append(",")
            }
        }
        selection.append(")")
        var deletedCount = 0
        try {
            val cursor: Cursor? = context.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, projection, selection.toString(),
                null, null
            )
            if (cursor != null) {
                removeFromQueue(songs)

                // Step 2: Remove files from card
                cursor.moveToFirst()
                while (!cursor.isAfterLast) {
                    val id: Int = cursor.getInt(0)
                    val name: String = cursor.getString(1)
                    try { // File.delete can throw a security exception
                        val f = File(name)
                        if (f.delete()) {
                            // Step 3: Remove selected track from the database
                            context.contentResolver.delete(
                                ContentUris.withAppendedId(
                                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                                    id.toLong()
                                ), null, null
                            )
                            deletedCount++
                        } else {
                            // I'm not sure if we'd ever get here (deletion would
                            // have to fail, but no exception thrown)
                            Log.e("MusicUtils", "Failed to delete file $name")
                        }
                        cursor.moveToNext()
                    } catch (ex: SecurityException) {
                        cursor.moveToNext()
                    } catch (e: NullPointerException) {
                        Log.e("MusicUtils", "Failed to find file $name")
                    }
                }
                cursor.close()
            }
            withContext(Dispatchers.Main) {
                context.showToast(context.getString(R.string.deleted_x_songs, deletedCount))
            }

        } catch (ignored: SecurityException) {
        }
    }

    fun songByGenre(genreId: Long): Song {
        return repository.getSongByGenre(genreId)
    }
}