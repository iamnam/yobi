/**
 * Yobi, Project Hosting SW
 *
 * Copyright 2012 NAVER Corp.
 * http://yobi.io
 *
 * @Author Yi EungJun
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package models;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Date;
import java.util.Formatter;
import java.util.List;
import java.util.concurrent.TimeUnit;

import javax.persistence.*;

import controllers.AttachmentApp;
import models.resource.GlobalResource;
import models.resource.Resource;
import models.resource.ResourceConvertible;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.apache.tika.Tika;

import models.enumeration.ResourceType;

import org.apache.tika.metadata.Metadata;
import org.apache.tika.mime.MediaType;
import play.data.validation.*;

import play.db.ebean.Model;
import play.libs.Akka;
import scala.concurrent.duration.Duration;
import scalax.file.NotDirectoryException;
import utils.FileUtil;
import utils.JodaDateUtil;

@Entity
public class Attachment extends Model implements ResourceConvertible {
    private static final long serialVersionUID = 7856282252495067924L;
    public static final Finder<Long, Attachment> find = new Finder<>(Long.class, Attachment.class);
    public static final int NOTHING_TO_ATTACH = 0;
    private static String uploadDirectory = "uploads";
    @Id
    public Long id;

    @Constraints.Required
    public String name;

    @Constraints.Required
    public String hash;

    @Enumerated(EnumType.STRING)
    public ResourceType containerType;

    public String mimeType;
    public Long size;
    public String containerId;

    private Date createdDate;

    /**
     * 주어진 {@code attach}와 내용이 같은 첨부 파일을 찾는다.
     *
     * 내용이 같은 첨부 파일이라고 하는 것은 이름과 파일의 내용이 같고 첨부된 리소스도 같은 것을 말한다.
     * 구체적으로 말하면 {@link Attachment#name}, {@link Attachment#hash},
     * {@link Attachment#containerType}, {@link Attachment#containerId}가 서로 같은 첨부이다.
     *
     * @param attach 이 첨부 파일과 내용이 같은 첨부 파일을 찾는다.
     * @return 발견된 첨부 파일
     */
    private static Attachment findBy(Attachment attach) {
        return find.where()
                .eq("name", attach.name)
                .eq("hash", attach.hash)
                .eq("containerType", attach.containerType)
                .eq("containerId", attach.containerId).findUnique();
    }

    /**
     * 파일의 해시값이 {@code hash}인 첨부 파일이 존재하는지의 여부를 반환한다.
     *
     * @param hash 해시값
     * @return 해시값이 {@code hash}와 같은 첨부가 존재하는지의 여부
     */
    public static boolean exists(String hash) {
        return find.where().eq("hash", hash).findRowCount() > 0;
    }

    /**
     * 주어진 {@code containerType}과 {@code containerId}에 대응하는 리소스에 첨부된 첨부 파일의 목록을 반환한다.
     *
     * {@code containerType}은 {@link models.resource.Resource#getType()}의 반환값과,
     * {@code containerId}는 {@link models.resource.Resource#getId()}의 반환값과 비교한다.
     *
     * @param containerType 첨부 파일이 첨부된 리소스의 타입
     * @param containerId 첨부 파일이 첨부된 리소스의 아이디
     * @return 첨부 파일의 목록
     */
    public static List<Attachment> findByContainer(
            ResourceType containerType, String containerId) {
        return find.where()
                .eq("containerType", containerType)
                .eq("containerId", containerId).findList();
    }

    /**
     * 주어진 {@code container}의 첨부된 첨부 파일의 목록을 반환한다.
     *
     * @param container 첨부 파일이 첨부된 리소스
     * @return 첨부 파일의 목록
     */
    public static List<Attachment> findByContainer(Resource container) {
        return findByContainer(container.getType(), container.getId());
    }

    /**
     * 주어진 {@code container}에 첨부된 첨부 파일의 갯수를 반환한다.
     *
     * when:
     *
     * @param container 첨부 파일이 첨부된 리소스
     * @return 첨부 파일의 목록
     */
    public static int countByContainer(Resource container) {
        return find.where()
                .eq("containerType", container.getType())
                .eq("containerId", container.getId()).findRowCount();
    }

    /**
     * {@code from}에 첨부된 모든 첨부 파일을 {@code to}로 옮긴다.
     *
     * when: 업로드 직후 일시적으로 사용자에게 첨부되었던 첨부 파일들을, 특정 리소스(이슈, 게시물 등)으로 옮기려 할 때
     *
     * @param from 첨부 파일이 원래 있었던 리소스
     * @param to 첨부 파일이 새로 옮겨질 리소스
     * @return
     */
    public static int moveAll(Resource from, Resource to) {
        List<Attachment> attachments = Attachment.findByContainer(from);
        for (Attachment attachment : attachments) {
            attachment.moveTo(to);
        }
        return attachments.size();
    }

    /**
     * {@code from}에 첨부된 파일중 파일 아이디가{@code selectedFileIds}에 해당하는 첨부 파일을 {@code to}로 옮긴다.
     *
     * when: 업로드 직후 일시적으로 사용자에게 첨부되었던 첨부 파일들을, 특정 리소스(이슈, 게시물 등)으로 옮기려 할 때
     *
     * @param from 첨부 파일이 원래 있었던 리소스
     * @param to 첨부 파일이 새로 옮겨질 리소스
     * @return
     */
    public static int moveOnlySelected(Resource from, Resource to, String[] selectedFileIds) {
        if(selectedFileIds.length == 0){
            return NOTHING_TO_ATTACH;
        }
        List<Attachment> attachments = Attachment.find.where().idIn(Arrays.asList(selectedFileIds)).findList();
        for (Attachment attachment : attachments) {
            if(attachment.containerId.equals(from.getId())
                    && attachment.containerType == from.getType()){
                attachment.moveTo(to);
            }
        }
        return attachments.size();
    }

    /**
     * 이 첨부 파일을 {@code to}로 옮긴다.
     *
     * @param to 첨부 파일이 새로 옮겨질 리소스
     * @return
     */
    public void moveTo(Resource to) {
        containerType = to.getType();
        containerId = to.getId();
        update();
    }

    /**
     * {@code file}을 업로드 디렉토리로 옮긴다.
     *
     * when: Play 프레임워크는 파일을 업로드하면 일단 임시 디렉토리에 보관하는데, 이 파일을 업로드 디렉토리로
     * 옮기려 할 때 사용한다.
     *
     * @param file
     * @return
     * @throws NoSuchAlgorithmException
     * @throws IOException
     */
    private static String moveFileIntoUploadDirectory(File file)
            throws NoSuchAlgorithmException, IOException {
        // Compute sha1 checksum.
        MessageDigest algorithm = MessageDigest.getInstance("SHA1");
        byte buf[] = new byte[10240];
        FileInputStream fis = new FileInputStream(file);
        for (int size = 0; size >= 0; size = fis.read(buf)) {
            algorithm.update(buf, 0, size);
        }
        Formatter formatter = new Formatter();
        for (byte b : algorithm.digest()) {
            formatter.format("%02x", b);
        }
        String hash = formatter.toString();
        formatter.close();
        fis.close();

        // Store the file.
        // Before do that, create upload directory if it doesn't exist.
        File uploads = new File(uploadDirectory);
        uploads.mkdirs();
        if (!uploads.isDirectory()) {
            throw new NotDirectoryException(
                    "'" + file.getAbsolutePath() + "' is not a directory.");
        }
        File attachedFile = new File(uploadDirectory, hash);
        boolean isMoved = file.renameTo(attachedFile);

        if(!isMoved){
            FileUtils.copyFile(file, attachedFile);
            file.delete();
        }

        // Close all resources.

        return hash;
    }

    /**
     * 업로드된 {@code file}을 주어진 {@code name}으로 {@code container}에 첨부한다.
     *
     * when: 업로드된 파일이 사용자에게 첨부될 때. 혹은 사용자를 거치지 않고 바로 다른 리소스로 첨부될 때.
     *
     * 업로드된 파일을 업로드 디렉토리로 옮긴다. 이 때 파일이름을 그 파일의 해시값으로 변경한다. 그 후 이 파일에
     * 대한 메타정보 및 첨부될 대상에 대한 정보를 이 엔터티에 담는다. 만약 이 엔터티와 같은 내용을 갖고 있는
     * 엔터티가 이미 존재한다면, 이미 {@code container}에 같은 첨부가 존재하고 있으므로 첨부하지 않고
     * {@code false}를 반환한다. 그렇지 않다면 첨부 후 {@code true}를 반환한다.
     *
     * @param file 첨부할 파일
     * @param name 파일 이름
     * @param container 파일이 첨부될 리소스
     * @return 파일이 새로 첨부되었다면 {@code true}, 이미 같은 첨부가 존재하여 첨부되지 않았다면 {@code false}
     * @throws IOException
     * @throws NoSuchAlgorithmException
     */
    @Transient
    public boolean store(File file, String name, Resource container) throws IOException, NoSuchAlgorithmException {
        // Store the file as its SHA1 hash in filesystem, and record its
        // metadata - containerType, containerId, size and hash - in Database.
        this.containerType = container.getType();
        this.containerId = container.getId();
        this.createdDate = JodaDateUtil.now();

        if (name == null) {
            this.name = file.getName();
        } else {
            this.name = name;
        }

        if (this.mimeType == null) {
            this.mimeType = FileUtil.detectMediaType(file, name).toString();
        }

        // the size must be set before it is moved.
        this.size = file.length();
        this.hash = Attachment.moveFileIntoUploadDirectory(file);

        // Add the attachment into the Database only if there is no same record.
        Attachment sameAttach = Attachment.findBy(this);
        if (sameAttach == null) {
            super.save();
            return true;
        } else {
            this.id = sameAttach.id;
            return false;
        }
   }

    /**
     * 업로드 디렉토리에서 이 첨부 파일의 해시값에 해당하는 파일을 가져온다.
     *
     * when: 파일을 다운로드 할 때
     *
     * @return 가져온 파일
     */
    public File getFile() {
        return new File(uploadDirectory, this.hash);
    }

    /**
     * 업로드 디렉토리를 설정한다.
     *
     * when: 테스트에서 업로드 디렉토리를 변경하기 위해 사용
     *
     * @param path 업로드 디렉토리의 경로
     */
    public static void setUploadDirectory(String path) {
        uploadDirectory = path;
    }

    /**
     * 이 첨부 파일의 해시값에 해당하는 파일의 업로드 디렉토리에 존재하는지 여부를 반환한다.
     *
     * when: 이 첨부 파일이 실제로 파일 시스템에 존재하고 있는지 검증하려고 할 때
     *
     * @param hash 파일을 찾을 때 비교해볼 해시값
     * @return 파일이 존재하는지의 여부
     */
    public static boolean fileExists(String hash) {
        return new File(uploadDirectory, hash).isFile();
    }

    /**
     * 이 첨부 파일을 삭제한다.
     *
     * when: 사용자가 첨부 파일 혹은 첨부 파일이 첨부된 리소스를 삭제했을 때
     */
    @Override
    public void delete() {
        super.delete();
        if (!exists(this.hash)) {
            new File(uploadDirectory, this.hash).delete();
        }
    }


    /**
     * 주어진 {@code container}에 첨부된 모든 첨부 파일을 삭제한다.
     *
     * when: 첨부 파일을 가질 수 있는 어떤 리소스가 삭제되었을 때
     *
     * @param container 첨부 파일을 삭제할 리소스
     */
    public static void deleteAll(Resource container) {
        List<Attachment> attachments = findByContainer(container);
        for (Attachment attachment : attachments) {
            attachment.delete();
        }
    }

    private String messageForLosingProject() {
        return "An attachment '" + this +"' lost the project it belongs to";
    }

    /**
     * 이 객체를 리소스로 반환한다.
     *
     * when: 권한검사시 사용
     *
     * @return 리소스
     */
    @Override
    public Resource asResource() {
        boolean isContainerProject = containerType.equals(ResourceType.PROJECT);
        final Project project;
        final Resource container;

        if (isContainerProject) {
            project = Project.find.byId(Long.parseLong(containerId));
            if (project == null) {
                throw new RuntimeException(messageForLosingProject());
            }
            container = project.asResource();
        } else {
            container = Resource.get(containerType, containerId);
            if (!(container instanceof GlobalResource)) {
                project = container.getProject();
                if (project == null) {
                    throw new RuntimeException(messageForLosingProject());
                }
            } else {
                project = null;
            }
        }

        if (project != null) {
            return new Resource() {
                @Override
                public String getId() {
                    return id.toString();
                }

                @Override
                public Project getProject() {
                    return project;
                }

                @Override
                public ResourceType getType() {
                    return ResourceType.ATTACHMENT;
                }

                @Override
                public Resource getContainer() {
                    return container;
                }
            };
        } else {
            return new GlobalResource() {
                @Override
                public String getId() {
                    return id.toString();
                }

                @Override
                public ResourceType getType() {
                    return ResourceType.ATTACHMENT;
                }

                @Override
                public Resource getContainer() {
                    return container;
                }
            };
        }
    }

    /**
     * Remove all of temporary files uploaded by users
     */
    private static void cleanupTemporaryUploadFilesWithSchedule() {
        Akka.system().scheduler().schedule(
                Duration.create(0, TimeUnit.SECONDS),
                Duration.create(AttachmentApp.TEMPORARYFILES_KEEPUP_TIME_MILLIS, TimeUnit.MILLISECONDS),
                new Runnable() {
                    @Override
                    public void run() {
                        try {
                            String result = removeUserTemporaryFiles();
                            play.Logger.info("User uploaded temporary files are cleaned up..." + result);
                        } catch (Exception e) {
                            play.Logger.warn("Failed!! User uploaded temporary files clean-up action failed!", e);
                        }
                    }

                    /**
                     * 사용자 임시 첨부 파일을 삭제
                     *
                     * 사용자에 의해 업로드 된지 {@code application.temporaryfiles.keep-up.time}초 이상 경과한
                     * 임시파일들은 서버에서 삭제한다.
                     *
                     * @return 전체 대상 파일 중 지운 파일 ex> (10 of 10)
                     */
                    private String removeUserTemporaryFiles() {
                        List<Attachment> attachmentList = Attachment.find.where()
                                .eq("containerType", ResourceType.USER)
                                .ge("createdDate", JodaDateUtil.beforeByMillis(AttachmentApp.TEMPORARYFILES_KEEPUP_TIME_MILLIS))
                                .findList();
                        int deletedFileCount = 0;
                        for (Attachment attachment : attachmentList) {
                            attachment.delete();
                            deletedFileCount++;
                        }
                        if( attachmentList.size() != deletedFileCount) {
                            play.Logger.error(
                                    String.format("Failed to delete user temporary files.\nExpected: %d  Actual: %d",
                                            attachmentList.size(), deletedFileCount)
                            );
                        }
                        return String.format("(%d of %d)", attachmentList.size(), deletedFileCount);
                    }
                },
                Akka.system().dispatcher()
        );
    }

    /**
     * when: Global의 onStart가 실행될 때 호출됩니다.
     */
    public static void onStart() {
        cleanupTemporaryUploadFilesWithSchedule();
    }
}
