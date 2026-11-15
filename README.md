# File Upload (Spring MVC, Java 8, JSP, ES5 / IE 11)

Auto-upload of files grouped by a **file manager number (`fileMngNo`)**.

- Files are uploaded automatically as soon as they are picked, one at a time.
- If there is no `fileMngNo` yet (new record) the server creates one on the first upload and every later file reuses it.
- The two upload boxes on the page (Evidence / Action Taken Evidence) **share ONE `fileMngNo`**.
- A page-level **Reject** button copies all files to a **new** `fileMngNo` (re-upload of the same files).
- A list of all `fileMngNo` values is shown below the Save button.

Plain **Spring MVC** (no Spring Boot, no `@GetMapping` / `@PostMapping`; only `@RequestMapping(method = ...)`), **Java 8**, JSP, and front-end code in **ES5** (IE 11 compatible, `XMLHttpRequest` only, **no `fetch`**, no `Promise`, no arrow functions, no `let`/`const`).

## Project layout

```
pom.xml                                  war, Spring 5.3, Jackson, H2, Jetty plugin
src/main/java/com/example/fileupload/
  FileUploadWebApp.java                  ALL Java in one file (see below)
src/main/resources/
  app.properties                         DB + storage settings
  schema.sql                             tables + sequences (run at startup)
src/main/webapp/
  WEB-INF/jsp/saf0101.jsp                the page
  js/fileuploader.js                     GridFileUploader component (ES5)
  js/saf0101.js                          page script (save, reject, number list)
  css/fileuploader.css                   styles
```

`FileUploadWebApp.java` contains these nested classes:

| Class | Role |
|---|---|
| `AppInitializer` | replaces `web.xml` (DispatcherServlet, UTF-8 filter, multipart limits 50 MB file / 200 MB request) |
| `WebConfig` | MVC config: JSP view resolver, multipart resolver, DataSource, `JdbcTemplate`, runs `schema.sql` |
| `FileController` | the `*.do` endpoints below |
| `AttachFileService` | disk storage + `ATTACH_FILE` rows, number sequences, reject (copy) |
| `PageController` | sample page `saf0101` and its save |
| `UploadErrorAdvice` | JSON error for size-limit / multipart failures |

## Run

Requires Java 8+ and Maven. The default database is a file based H2 database in `./data`.

### Jetty (local)

```bash
mvn clean jetty:run
```

Open: `http://localhost:9390/saf0101`

(`mvn spring-boot:run` no longer exists - the project is not Spring Boot any more.)

### Tomcat 8.5 / 9

```bash
mvn clean package
```

Deploy `target/fileupload.war`. The war name becomes part of the URL:
`http://localhost:8080/fileupload/saf0101`

## URLs

Base (Jetty): `http://localhost:9390` - context path is `/`. On Tomcat add `/fileupload` in front.

| What | Method | URL |
|---|---|---|
| Page | GET | `/saf0101` (`/` redirects to it, `/saf0101?id=1` opens a saved record) |
| Save record | POST | `/saf0101/save` |
| Upload | POST | `/core/file/uploadFile.do?mode=C&pathKey=EVIDENCE&pgmId=SAF0101&fileMngNo=` |
| List files of a number | GET | `/core/file/listFile.do?fileMngNo=FM0000000001&pathKey=EVIDENCE` (`pathKey` optional) |
| List all numbers | GET | `/core/file/listMngNo.do` |
| Reject (copy to new number) | POST | `/core/file/rejectFile.do` (`fileMngNo`) |
| Delete | POST | `/core/file/deleteFile.do` (`fileMngNo`, `fileRegNos` comma separated) |
| Download | GET | `/core/file/downloadFile.do?fileMngNo=FM0000000001&fileRegNo=FR0000000001` |

Full download URL example:

```
http://localhost:9390/core/file/downloadFile.do?fileMngNo=FM0000000001&fileRegNo=FR0000000001
```

Both parameters are required and the file is only returned if that `fileRegNo` belongs to that `fileMngNo` (otherwise 404). Take `fileRegNo` from `listFile.do` (`FILE_REG_NO`).

### Response format

Errors are returned as HTTP 200 with `{"ErrorCode":-1,"ErrorMsg":"..."}`. Success has `"ErrorCode":0`.

Upload success:

```json
{"ErrorCode":0,
 "FILE_RESULT":{"FILE_MNG_NO":"FM0000000001","FILE_REG_NO":"FR0000000001","LOGI_FILE_NM":"a.txt","FILE_SIZE":2},
 "FILE_RESULT_LIST":[ ... ]}
```

## How it works

### Auto-upload with a new `fileMngNo`

1. Files picked in a box are queued and uploaded **one at a time**.
2. With an empty `fileMngNo` the first upload is sent without it. The server creates a new number (database sequence, so two simultaneous first uploads can never get the same number) and returns it in `FILE_RESULT.FILE_MNG_NO`.
3. The number is stored in the control and used for every following file.
4. The Evidence and Action Taken Evidence boxes are linked (`ev.link(ac)`): whoever gets the number first hands it to the other, and while no number exists only one box uploads at a time. Each box lists only its own files (stored `PATH_KEY`).
5. `getFileMngNo()` returns the current number; `save()` in the page puts it into the hidden input `fileMngNo` and submits the form.

### Reject

The page-level **Reject** button (beside Save, not inside the upload boxes) calls `rejectFile.do`. The server copies every file of the current number on disk (new `FILE_REG_NO`) and in `ATTACH_FILE` under a **new** `fileMngNo`. The old set is left untouched. If a copy fails, the copies already made are removed. Both boxes switch to the new number. The new number is stored in the record when **Save** is pressed.

### Number list

Below Save: every `fileMngNo` with file count, total size and upload date. The number used by the current record is bold. Click a row to see its files; click a file to download it. The list refreshes after every upload, delete and reject.

## JavaScript API (`GridFileUploader`)

```js
GridFileUploader.baseUrl = '/context-path';          // set once (the JSP does this)

var ev = new GridFileUploader({ containerId: 'evBox', title: 'Evidence', onChange: fn });
var ac = new GridFileUploader({ containerId: 'acBox', title: 'Action Taken Evidence' });
ev.link(ac);                                          // share one fileMngNo

ev.setFile({ fileMngNo: '', pathKey: 'EVIDENCE', pgmId: 'SAF0101' });   // '' = new record
ev.getFileMngNo();     // current number ('' if nothing uploaded yet)
ev.getFileList();
ev.isBusy();           // true while files are queued / uploading
ev.hasFiles();
ev.reject(noConfirm, cb);   // copy to a new number (used by the page button)
```

Options: `containerId`, `title`, `editable` (default `true`), `maxSizeMB` (default 50), `onChange(fileMngNo)`, `onReject(oldNo, newNo)`.

## Configuration (`src/main/resources/app.properties`)

| Key | Meaning |
|---|---|
| `db.driver`, `db.url`, `db.username`, `db.password` | database (default: file based H2) |
| `app.file.root` | folder where uploaded files are stored (default `./data/upload`) |
| `app.file.deny-ext` | blocked extensions, comma separated |

Port (Jetty) is set in `pom.xml` (`9390`).

## Security / validation

- `pathKey` must match `[A-Za-z0-9_-]{1,50}` and `fileMngNo` must be letters/digits only (no path traversal).
- Blocked extensions (`app.file.deny-ext`), client path stripped from the file name, files stored under a system generated name.
- Delete and download only work on files that belong to the given `fileMngNo`.
- Downloads are sent as attachments with `X-Content-Type-Options: nosniff`.

## Notes and known limits

- **Database:** `schema.sql` and the `NEXT VALUE FOR ...` sequence queries are H2 syntax. Change them for Oracle / MySQL. For production, replace the `DataSource` bean in `WebConfig` (for example with a JNDI lookup).
- **Multipart:** uses the Servlet 3 resolver (`StandardServletMultipartResolver`). If you use Commons FileUpload, swap the multipart bean.
- **Upload save:** files are written with `Files.copy` from the upload stream, not `MultipartFile.transferTo()` (which fails on Jetty with absolute paths).
- **Data folders:** `data/` (database + uploaded files) is created in the working directory. Do not delete it while the app runs.
- **Status:** the Spring MVC conversion, the Jetty plugin, and the `Files.copy` upload fix were written without building or running the project. The earlier Spring Boot version of the same logic was tested end to end (upload, shared number, list, download, reject, delete, save). The browser UI and IE 11 were not tested.
- `.gitignore` ignores `data/`, `target/` and `images/`.
