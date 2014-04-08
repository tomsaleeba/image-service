package au.org.ala.images

import au.org.ala.images.tiling.TileFormat
import grails.transaction.Transactional
import org.apache.commons.imaging.Imaging
import org.apache.commons.imaging.common.IImageMetadata
import org.apache.commons.imaging.formats.jpeg.JpegImageMetadata
import org.apache.commons.imaging.formats.tiff.TiffField
import org.apache.commons.imaging.formats.tiff.constants.TiffConstants
import org.apache.commons.imaging.formats.tiff.taginfos.TagInfo
import org.apache.commons.io.FileUtils
import org.apache.commons.io.FilenameUtils
import org.apache.commons.lang.StringUtils
import org.apache.tika.detect.Detector
import org.apache.tika.metadata.Metadata
import org.apache.tika.mime.MediaType
import org.apache.tika.parser.AutoDetectParser
import org.codehaus.groovy.grails.plugins.codecs.MD5Codec
import org.codehaus.groovy.grails.plugins.codecs.SHA1Codec
import org.springframework.web.multipart.MultipartFile

import java.text.SimpleDateFormat
import java.util.concurrent.ConcurrentLinkedQueue

@Transactional
class ImageService {

    def imageStoreService
    def tagService
    def grailsApplication
    def logService


    private static Queue<BackgroundTask> _backgroundQueue = new ConcurrentLinkedQueue<BackgroundTask>()
    private static Queue<BackgroundTask> _tilingQueue = new ConcurrentLinkedQueue<BackgroundTask>()

    private static int BACKGROUND_TASKS_BATCH_SIZE = 100

    public Image storeImage(MultipartFile imageFile, String uploader) {

        if (imageFile) {
            // Store the image
            def originalFilename = imageFile.originalFilename
            def image = storeImageBytes(imageFile?.bytes, originalFilename, imageFile.size, imageFile.contentType, uploader)
            scheduleArtifactGeneration(image.id)
            return image
        }
        return null
    }

    public int getImageTaskQueueLength() {
        return _backgroundQueue.size()
    }

    public int getTilingTaskQueueLength() {
        return _tilingQueue.size()
    }

    private Image storeImageBytes(byte[] bytes, String originalFilename, long filesize, String contentType, String uploaderId) {
        CodeTimer ct = new CodeTimer("Store Image ${originalFilename}")
        def md5Hash = MD5Codec.encode(bytes)
        def sha1Hash = SHA1Codec.encode(bytes)

//        def existing = Image.findByContentMD5Hash(hash);
//        if (existing) {
//            println "Image already exists in database!"
//            return existing;
//        }

        def extension = FilenameUtils.getExtension(originalFilename) ?: 'jpg'

        def imgDesc = imageStoreService.storeImage(bytes)

        // Create the image record, and set the various attributes
        Image image = new Image(imageIdentifier: imgDesc.imageIdentifier, contentMD5Hash: md5Hash, contentSHA1Hash: sha1Hash, uploader: uploaderId)
        image.fileSize = filesize
        image.mimeType = contentType
        image.dateUploaded = new Date()
        image.originalFilename = originalFilename
        image.extension = extension

        image.dateTaken = getImageTakenDate(bytes) ?: image.dateUploaded

        image.height = imgDesc.height
        image.width = imgDesc.width

        image.save(flush: true, failOnError: true)

        def md = getImageMetadataFromBytes(bytes)
        md.each { kvp ->
            if (kvp.key && kvp.value) {
                setMetaDataItem(image, MetaDataSourceType.Embedded, kvp.key, kvp.value)
            }
        }

        ct.stop(true)
        return image
    }

    public String getMetadataItemValue(Image image, String key, MetaDataSourceType source = MetaDataSourceType.SystemDefined) {
        def results = ImageMetaDataItem.executeQuery("select value from ImageMetaDataItem where image = :image and lower(name) = :key and source=:source", [image: image, key: key, source: source])
        if (results) {
            return results[0]
        }

        return null
    }

    public Map getMetadataItemValuesForImages(List<Image> images, String key, MetaDataSourceType source = MetaDataSourceType.SystemDefined) {
        if (!images || !key) {
            return [:]
        }

        def ct = new CodeTimer("getMetadataItemValuesForImages (key ${key}) for ${images.size()} images")
        def results = ImageMetaDataItem.executeQuery("select md.value, md.image.id from ImageMetaDataItem md where md.image in (:images) and lower(name) = :key and source=:source", [images: images, key: key.toLowerCase(), source: source])
        def fr = [:]
        results.each {
            fr[it[1]] = it[0]
        }
        ct.stop(true)
        return fr
    }

    public Map getAllUrls(String imageIdentifier) {
        return imageStoreService.getAllUrls(imageIdentifier)
    }

    public String getImageUrl(String imageIdentifier) {
        return imageStoreService.getImageUrl(imageIdentifier)
    }

    public String getImageThumbUrl(String imageIdentifier) {
        return imageStoreService.getImageThumbUrl(imageIdentifier)
    }

    public String getImageSquareThumbUrl(String imageIdentifier) {
        return imageStoreService.getImageSquareThumbUrl(imageIdentifier)
    }


    public String getImageTilesRootUrl(String imageIdentifier) {
        return imageStoreService.getImageTilesRootUrl(imageIdentifier)
    }


    private static Date getImageTakenDate(byte[] bytes) {
        try {
            IImageMetadata metadata = Imaging.getMetadata(bytes)
            if (metadata && metadata instanceof JpegImageMetadata) {
                JpegImageMetadata jpegMetadata = metadata

                def date = getImageTagValue(jpegMetadata,TiffConstants.EXIF_TAG_DATE_TIME_ORIGINAL)
                if (date) {
                    def sdf = new SimpleDateFormat("yyyy:MM:dd hh:mm:ss")
                    return sdf.parse(date.toString())
                }
            }
        } catch (Exception ex) {
            return null
        }
    }

    private static Object getImageTagValue(JpegImageMetadata jpegMetadata, TagInfo tagInfo) {
        TiffField field = jpegMetadata.findEXIFValue(tagInfo);
        if (field) {
            return field.value
        }
    }

    private static Map<String, Object> getImageMetadataFromBytes(byte[] bytes) {
        def md = [:]
        try {
            IImageMetadata metadata = Imaging.getMetadata(bytes)
            if (metadata) {
                metadata.items.each {
                    md[it.keyword] = it.text
                }
            }
        } catch (Exception ex) {

        }
        return md
    }

    def scheduleArtifactGeneration(long imageId) {
        _backgroundQueue.add(new ImageBackgroundTask(imageId, this, ImageTaskType.Thumbnail))
        _tilingQueue.add(new ImageBackgroundTask(imageId, this, ImageTaskType.TMSTile))
    }

    def scheduleThumbnailGeneration(long imageId) {
        _backgroundQueue.add(new ImageBackgroundTask(imageId, this, ImageTaskType.Thumbnail))
    }

    def scheduleTileGeneration(long imageId) {
        _tilingQueue.add(new ImageBackgroundTask(imageId, this, ImageTaskType.TMSTile))
    }

    def scheduleKeywordRebuild(long imageId) {
        _backgroundQueue.add(new ImageBackgroundTask(imageId, this, ImageTaskType.KeywordRebuild))
    }

    def schedulePollInbox(String userId) {
        def task = new PollInboxBackgroundTask(this, userId)
        _backgroundQueue.add(task)
        return task.batchId
    }

    public void processBackgroundTasks() {
        int taskCount = 0
        BackgroundTask task = null

        while (taskCount < BACKGROUND_TASKS_BATCH_SIZE && (task = _backgroundQueue.poll()) != null) {
            if (task) {
                task.execute()
                taskCount++
            }
        }
    }

    public void processTileBackgroundTasks() {
        int taskCount = 0
        BackgroundTask task = null
        while (taskCount < BACKGROUND_TASKS_BATCH_SIZE && (task = _tilingQueue.poll()) != null) {
            if (task) {
                task.execute()
                taskCount++
            }
        }
    }

    ThumbDimensions generateImageThumbnails(String imageIdentifier) {
        return imageStoreService.generateImageThumbnails(imageIdentifier)
    }

    void generateTMSTiles(String imageIdentifier) {
        imageStoreService.generateTMSTiles(imageIdentifier)
    }

    def deleteImage(Image image) {

        if (image) {

            // need to delete it from user selections
            def selected = SelectedImage.findAllByImage(image)
            selected.each { selectedImage ->
                selectedImage.delete()
            }

            // Need to delete tags
            def tags = ImageTag.findAllByImage(image)
            tags.each { tag ->
                tag.delete()
            }

            // Delete keywords
            def keywords = ImageKeyword.findAllByImage(image)
            keywords.each { keyword ->
                keyword.delete()
            }

            // if this image is a subimage, also need to delete any subimage rectangle records
            if (image.parent) {
                def subimages = Subimage.findAllBySubimage(image)
                subimages.each { subimage ->
                    subimage.delete()
                }
            }

            // This image may also be a parent image
            def subimages = Subimage.findAllByParentImage(image)
            subimages.each { subimage ->
                // need to detach this image from the child images, but we do not actually delete the sub images. They
                // will live on as root images in their own right
                subimage.subimage.parent = null
                subimage.delete()
            }

            // and delete domain object
            image.delete(flush: true, failonerror: true)

            // Finally need to delete images on disk. This might fail (if the file is held open somewhere), but that's ok, we can clean up later.
            imageStoreService.deleteImage(image?.imageIdentifier)

            return true
        }

        return false
    }

    List<File> listStagedImages() {
        def files = []
        def inboxLocation = grailsApplication.config.imageservice.imagestore.inbox as String
        def inboxDirectory = new File(inboxLocation)
        inboxDirectory.eachFile { File file ->
            files << file
        }
        return files
    }

    def importFile(File file, String batchId, String userId) {

        CodeTimer ct = new CodeTimer("Import file ${file?.absolutePath}")

        if (!file || !file.exists()) {
            throw new RuntimeException("Could not read file ${file?.absolutePath} - Does not exist")
        }


        Image image = null

        def fieldDefinitions = ImportFieldDefinition.list()

        Image.withNewTransaction {

            // Create the image domain object
            def bytes = file.getBytes()
            def mimeType = detectMimeTypeFromBytes(bytes, file.name)
            image = storeImageBytes(bytes, file.name, file.length(),mimeType, userId)

            if (image && batchId) {
                setMetaDataItem(image, MetaDataSourceType.SystemDefined,  "importBatchId", batchId)
            }

            // Is there any extra data to be applied to this image?
            if (fieldDefinitions) {
                fieldDefinitions.each { fieldDef ->
                    setMetaDataItem(image, MetaDataSourceType.SystemDefined, fieldDef.fieldName, ImportFieldValueExtractor.extractValue(fieldDef, file))
                }
            }
            def dimensions = generateImageThumbnails(image.imageIdentifier)
            image.thumbHeight = dimensions?.height
            image.thumbWidth = dimensions?.width
            image.squareThumbSize = dimensions?.squareThumbSize

            image.save(flush: true, failOnError: true)
        }

        // If we get here, and the image is not null, it means it has been committed to the database and we can remove the file from the inbox
        if (image) {
            if (!FileUtils.deleteQuietly(file)) {
                file.deleteOnExit()
            }
            // also we should do the thumb generation (we'll defer tiles until after the load, as it will slow everything down)
            scheduleTileGeneration(image.id)
        }
    }

    def pollInbox(String batchId, String userId) {
        def inboxLocation = grailsApplication.config.imageservice.imagestore.inbox as String
        def inboxDirectory = new File(inboxLocation)

        inboxDirectory.eachFile { File file ->
            _backgroundQueue.add(new ImportFileBackgroundTask(file, this, batchId, userId))
        }

    }

    def setMetaDataItem(Image image, MetaDataSourceType source, String key, String value) {


        if (image && StringUtils.isNotEmpty(key?.trim()) && StringUtils.isNotEmpty(value?.trim())) {
            
            // See if we already have an existing item...
            def existing = ImageMetaDataItem.findByImageAndNameAndSource(image, key, source)
            if (existing) {
                existing.value = value
            } else {
                def md = new ImageMetaDataItem(image: image, name: key, value: value, source: source)
                md.save()
                image.addToMetadata(md)
            }
            image.save()
            return true
        } else {
            logService.debug("Not Setting metadata item! Image ${image?.id} key: ${key} value: ${value}")
        }

        return false
    }

    def removeMetaDataItem(Image image, String key, MetaDataSourceType source) {
        def count = 0
        def items = ImageMetaDataItem.findAllByImageAndNameAndSource(image, key, source)
        if (items) {
            items.each { md ->
                count++
                md.delete()
            }
        }
        return count > 0
    }

    private Image importFromInbox(File file, String batchId, String userId) {
        logService.log("Importing file from ${file.absolutePath}")

        if (!file.exists()) {
            throw new RuntimeException("File not found: ${file.absolutePath}")
        }

        try {
            def bytes = file.getBytes()
            def mimeType = detectMimeTypeFromBytes(bytes, file.name)
            def image = storeImageBytes(bytes, file.name, file.length(),mimeType, userId)

            if (image && batchId) {
                setMetaDataItem(image, MetaDataSourceType.SystemDefined,  "importBatchId", batchId)
            }

            return image
        } catch (Throwable ex) {
            logService.error("Error importing from inbox", ex)
            ex.printStackTrace()
        }
        return null
    }

    private static String detectMimeTypeFromBytes(byte[] bytes, String filename) {
        def bais = new ByteArrayInputStream(bytes)
        def bis = new BufferedInputStream(bais);
        try {
            AutoDetectParser parser = new AutoDetectParser();
            Detector detector = parser.getDetector();
            Metadata md = new Metadata();
            if (filename) {
                md.add(Metadata.RESOURCE_NAME_KEY, filename);
            }
            MediaType mediaType = detector.detect(bis, md);
        return mediaType.toString();
        } finally {
            if (bais) {
                bais.close()
            }
            if (bis) {
                bis.close()
            }
        }
    }

    public Image createSubimage(Image parentImage, int x, int y, int width, int height, String userId) {

        if (x < 0) {
            x = 0;
        }
        if (y < 0) {
            y = 0;
        }

        def results = imageStoreService.retrieveImageRectangle(parentImage.imageIdentifier, x, y, width, height)
        if (results.bytes) {
            int subimageIndex = Subimage.countByParentImage(parentImage) + 1
            def filename = "${parentImage.originalFilename}_subimage_${subimageIndex}"
            def subimage = storeImageBytes(results.bytes,filename, results.bytes.length, results.contentType, userId)

            def subimageRect = new Subimage(parentImage: parentImage, subimage: subimage, x: x, y: y, height: height, width: width)
            subimageRect.save()
            subimage.parent = parentImage

            scheduleArtifactGeneration(subimage.id)

            return subimage
        }
    }

    def Map getImageInfoMap(Image image) {
        def map = [
                imageId: image.imageIdentifier,
                height: image.height,
                width: image.width,
                tileZoomLevels: image.zoomLevels,
                thumbHeight: image.thumbHeight,
                thumbWidth: image.thumbWidth,
                filesize: image.fileSize,
                mimetype: image.mimeType
        ]
        def urls = getAllUrls(image.imageIdentifier)
        urls.each { kvp ->
            map[kvp.key] = kvp.value
        }
        return map
    }

    def createNextTileJob() {
        def task = _tilingQueue.poll() as ImageBackgroundTask
        if (task == null) {
            return [success:false, message:"No tiling jobs available at this time."]
        } else {
            def image = Image.get(task.imageId)
            if (task) {
                // Create a new pending job
                def ticket = UUID.randomUUID().toString()
                def job = new OutsourcedJob(image: image, taskType: ImageTaskType.TMSTile, expectedDurationInMinutes: 15, ticket: ticket)
                job.save()
                return [success: true, imageId: image.imageIdentifier, jobTicket: ticket, tileFormat: TileFormat.JPEG]
            } else {
                return [success:false, message: "Internal error!"]
            }
        }
    }

}
