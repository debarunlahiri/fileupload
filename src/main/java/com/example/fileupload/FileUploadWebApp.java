package com.example.fileupload;

import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import javax.servlet.DispatcherType;
import javax.servlet.MultipartConfigElement;
import javax.servlet.ServletContext;
import javax.servlet.ServletRegistration;
import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.PropertySource;
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.DataSourceInitializer;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.stereotype.Controller;
import org.springframework.stereotype.Service;
import org.springframework.ui.Model;
import org.springframework.web.WebApplicationInitializer;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.filter.CharacterEncodingFilter;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MultipartRequest;
import org.springframework.web.multipart.support.StandardServletMultipartResolver;
import org.springframework.web.servlet.DispatcherServlet;
import org.springframework.web.servlet.ViewResolver;
import org.springframework.web.servlet.config.annotation.DefaultServletHandlerConfigurer;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.view.InternalResourceViewResolver;
import org.springframework.web.servlet.view.JstlView;

/**
 * File upload on plain Spring MVC (no Spring Boot, no @GetMapping/@PostMapping)
 * in ONE file. Servlet 3.0+ container (Tomcat 8.5/9), Java 8.
 *
 * - AppInitializer : replaces web.xml (DispatcherServlet, UTF-8 filter,
 * multipart limits)
 * - WebConfig : MVC config (JSP view resolver, multipart resolver, DataSource,
 * schema.sql)
 * - FileController : uploadFile.do / listFile.do / listMngNo.do / rejectFile.do
 * / deleteFile.do / downloadFile.do
 * - AttachFileService : disk storage + ATTACH_FILE rows, number sequences,
 * reject (copy to new fileMngNo)
 * - PageController : sample page saf0101.jsp (one fileMngNo shared by both
 * upload boxes)
 * - UploadErrorAdvice : JSON error for size-limit failures
 */
public class FileUploadWebApp {

    private FileUploadWebApp() {
    }

    // ======================================================================
    /**
     * web.xml replacement - found automatically by the servlet container (Servlet
     * 3.0).
     */
    public static class AppInitializer implements WebApplicationInitializer {

        private static final long MAX_FILE = 50L * 1024 * 1024;
        private static final long MAX_REQUEST = 200L * 1024 * 1024;

        @Override
        public void onStartup(ServletContext sc) {
            AnnotationConfigWebApplicationContext ctx = new AnnotationConfigWebApplicationContext();
            ctx.register(WebConfig.class);

            ServletRegistration.Dynamic dispatcher = sc.addServlet("dispatcher", new DispatcherServlet(ctx));
            dispatcher.setLoadOnStartup(1);
            dispatcher.addMapping("/");
            dispatcher.setMultipartConfig(new MultipartConfigElement(
                    System.getProperty("java.io.tmpdir"), MAX_FILE, MAX_REQUEST, 0));

            sc.addFilter("encodingFilter", new CharacterEncodingFilter("UTF-8", true))
                    .addMappingForUrlPatterns(EnumSet.of(DispatcherType.REQUEST), false, "/*");
        }
    }

    // ======================================================================
    @Configuration
    @EnableWebMvc
    @ComponentScan(basePackages = "com.example.fileupload")
    @PropertySource("classpath:app.properties")
    public static class WebConfig implements WebMvcConfigurer {

        /** resolves ${...} in @Value */
        @Bean
        public static PropertySourcesPlaceholderConfigurer propertyConfigurer() {
            return new PropertySourcesPlaceholderConfigurer();
        }

        @Bean
        public ViewResolver viewResolver() {
            InternalResourceViewResolver r = new InternalResourceViewResolver();
            r.setViewClass(JstlView.class);
            r.setPrefix("/WEB-INF/jsp/");
            r.setSuffix(".jsp");
            return r;
        }

        /** Servlet 3.0 multipart (limits are set in AppInitializer) */
        @Bean(name = "multipartResolver")
        public StandardServletMultipartResolver multipartResolver() {
            return new StandardServletMultipartResolver();
        }

        /** serves /js/** and /css/** from the webapp folder */
        @Override
        public void configureDefaultServletHandling(DefaultServletHandlerConfigurer configurer) {
            configurer.enable();
        }

        /**
         * Default: file based H2. In production use your own DataSource (e.g. JNDI)
         * here.
         */
        @Bean
        public DataSource dataSource(@Value("${db.driver}") String driver,
                @Value("${db.url}") String url,
                @Value("${db.username}") String user,
                @Value("${db.password}") String password) {
            DriverManagerDataSource ds = new DriverManagerDataSource();
            ds.setDriverClassName(driver);
            ds.setUrl(url);
            ds.setUsername(user);
            ds.setPassword(password);
            return ds;
        }

        @Bean
        public JdbcTemplate jdbcTemplate(DataSource ds) {
            return new JdbcTemplate(ds);
        }

        /** runs schema.sql at startup (all statements are IF NOT EXISTS) */
        @Bean
        public DataSourceInitializer dataSourceInitializer(DataSource ds) {
            ResourceDatabasePopulator populator = new ResourceDatabasePopulator();
            populator.addScript(new ClassPathResource("schema.sql"));
            DataSourceInitializer init = new DataSourceInitializer();
            init.setDataSource(ds);
            init.setDatabasePopulator(populator);
            return init;
        }
    }

    // ======================================================================
    /**
     * File storage + metadata.
     *
     * FILE_MNG_NO groups the files of one upload control. When a caller has no
     * number yet (new record) a new one is created here, once, from a DB sequence
     * (atomic, so concurrent first uploads can never collide).
     */
    @Service
    public static class AttachFileService {

        private static final Pattern SAFE_KEY = Pattern.compile("^[A-Za-z0-9_\\-]{1,50}$");
        private static final Pattern SAFE_NO = Pattern.compile("^[A-Za-z0-9]{1,20}$");

        private final JdbcTemplate jdbc;
        private final Path root;
        private final Set<String> denyExt;

        @Autowired
        public AttachFileService(JdbcTemplate jdbc,
                @Value("${app.file.root}") String root,
                @Value("${app.file.deny-ext}") String denyExt) {
            this.jdbc = jdbc;
            this.root = Paths.get(root).toAbsolutePath().normalize();
            this.denyExt = new HashSet<String>(Arrays.asList(denyExt.toLowerCase(Locale.ROOT).split("\\s*,\\s*")));
        }

        /* ---------- numbers ---------- */

        public String newFileMngNo() {
            Long n = jdbc.queryForObject("SELECT NEXT VALUE FOR SEQ_FILE_MNG", Long.class);
            return String.format("FM%010d", n);
        }

        private String newFileRegNo() {
            Long n = jdbc.queryForObject("SELECT NEXT VALUE FOR SEQ_FILE_REG", Long.class);
            return String.format("FR%010d", n);
        }

        /**
         * "", null, "null", "undefined" => empty. Anything else must be a valid number.
         */
        public static String cleanMngNo(String v) {
            if (v == null) {
                return "";
            }
            String s = v.trim();
            if (s.isEmpty() || "null".equalsIgnoreCase(s) || "undefined".equalsIgnoreCase(s)) {
                return "";
            }
            if (!SAFE_NO.matcher(s).matches()) {
                throw new IllegalArgumentException("Invalid fileMngNo");
            }
            return s;
        }

        /* ---------- save ---------- */

        public Map<String, Object> save(MultipartFile file, String fileMngNo, String pathKey, String pgmId)
                throws IOException {

            if (!SAFE_KEY.matcher(pathKey == null ? "" : pathKey).matches()) {
                throw new IllegalArgumentException("Invalid pathKey");
            }
            String orgName = cleanName(file.getOriginalFilename());
            String ext = "";
            int dot = orgName.lastIndexOf('.');
            if (dot > 0 && dot < orgName.length() - 1) {
                ext = orgName.substring(dot + 1).toLowerCase(Locale.ROOT);
            }
            if (denyExt.contains(ext)) {
                throw new IllegalArgumentException("File type not allowed: ." + ext);
            }

            String regNo = newFileRegNo();
            String detail = pathKey + "/" + new SimpleDateFormat("yyyy/MM/dd").format(new Date());
            Path dir = root.resolve(detail).normalize();
            if (!dir.startsWith(root)) {
                throw new IllegalArgumentException("Invalid path");
            }
            Files.createDirectories(dir);
            Path target = dir.resolve(regNo);
            // copy the stream ourselves: MultipartFile.transferTo() -> Part.write()
            // resolves absolute
            // paths against the container temp dir on Jetty, so it fails there
            InputStream in = file.getInputStream();
            try {
                Files.copy(in, target);
            } finally {
                in.close();
            }

            try {
                jdbc.update("INSERT INTO ATTACH_FILE (FILE_REG_NO, FILE_MNG_NO, BASE_PATH, DETAIL_PATH, "
                        + "PHIS_FILE_NM, LOGI_FILE_NM, FILE_EXT, FILE_SIZE, PGM_ID, PATH_KEY) VALUES (?,?,?,?,?,?,?,?,?,?)",
                        regNo, fileMngNo, root.toString(), detail, regNo, orgName, ext, file.getSize(), pgmId, pathKey);
            } catch (RuntimeException e) {
                Files.deleteIfExists(target); // keep disk and DB consistent
                throw e;
            }

            Map<String, Object> r = new LinkedHashMap<String, Object>();
            r.put("FILE_MNG_NO", fileMngNo);
            r.put("FILE_REG_NO", regNo);
            r.put("LOGI_FILE_NM", orgName);
            r.put("FILE_SIZE", file.getSize());
            return r;
        }

        /* ---------- read ---------- */

        /** files of one number; pathKey (optional) limits it to one upload box */
        public List<Map<String, Object>> list(String fileMngNo, String pathKey) {
            String sql = "SELECT LOGI_FILE_NM, FILE_SIZE, FILE_REG_NO, FILE_MNG_NO, PATH_KEY "
                    + "FROM ATTACH_FILE WHERE FILE_MNG_NO = ?";
            if (pathKey != null && !pathKey.isEmpty()) {
                if (!SAFE_KEY.matcher(pathKey).matches()) {
                    throw new IllegalArgumentException("Invalid pathKey");
                }
                return jdbc.queryForList(sql + " AND PATH_KEY = ? ORDER BY REG_DT, FILE_REG_NO", fileMngNo, pathKey);
            }
            return jdbc.queryForList(sql + " ORDER BY REG_DT, FILE_REG_NO", fileMngNo);
        }

        /**
         * one row per file manager no: file count, total size and first upload time
         * (newest first)
         */
        public List<Map<String, Object>> listMngNos() {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT FILE_MNG_NO, COUNT(*) AS FILE_CNT, SUM(FILE_SIZE) AS TOTAL_SIZE, MIN(REG_DT) AS REG_DT "
                            + "FROM ATTACH_FILE GROUP BY FILE_MNG_NO ORDER BY MIN(REG_DT) DESC, FILE_MNG_NO DESC");
            for (Map<String, Object> r : rows) {
                Object dt = r.get("REG_DT");
                r.put("REG_DT",
                        dt == null ? "" : String.valueOf(dt).substring(0, Math.min(19, String.valueOf(dt).length())));
            }
            return rows;
        }

        public Map<String, Object> find(String fileMngNo, String fileRegNo) {
            try {
                return jdbc.queryForMap("SELECT * FROM ATTACH_FILE WHERE FILE_REG_NO = ? AND FILE_MNG_NO = ?",
                        fileRegNo, fileMngNo);
            } catch (EmptyResultDataAccessException e) {
                return null;
            }
        }

        public Path physicalPath(Map<String, Object> row) {
            Path p = root.resolve(String.valueOf(row.get("DETAIL_PATH")))
                    .resolve(String.valueOf(row.get("PHIS_FILE_NM"))).normalize();
            if (!p.startsWith(root)) {
                throw new IllegalArgumentException("Invalid path");
            }
            return p;
        }

        /* ---------- reject ---------- */

        /**
         * Re-uploads every file of {@code oldMngNo} under a NEW file manager no:
         * each file is copied on disk (new FILE_REG_NO) and gets a new row.
         * The old set is left untouched. If anything fails, copies already made
         * are removed so no half-finished new set remains.
         */
        public String reject(String oldMngNo) throws IOException {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT * FROM ATTACH_FILE WHERE FILE_MNG_NO = ? ORDER BY REG_DT, FILE_REG_NO", oldMngNo);
            if (rows.isEmpty()) {
                throw new IllegalArgumentException("No files found for " + oldMngNo);
            }
            String newNo = newFileMngNo();
            List<Path> copied = new java.util.ArrayList<Path>();
            List<String> regs = new java.util.ArrayList<String>();
            try {
                for (Map<String, Object> r : rows) {
                    Path src = physicalPath(r);
                    if (!Files.isRegularFile(src)) {
                        throw new IOException("Missing file on disk: " + r.get("LOGI_FILE_NM"));
                    }
                    String regNo = newFileRegNo();
                    String detail = String.valueOf(r.get("DETAIL_PATH"));
                    Path dst = root.resolve(detail).resolve(regNo).normalize();
                    if (!dst.startsWith(root)) {
                        throw new IllegalArgumentException("Invalid path");
                    }
                    Files.copy(src, dst);
                    copied.add(dst);
                    jdbc.update("INSERT INTO ATTACH_FILE (FILE_REG_NO, FILE_MNG_NO, BASE_PATH, DETAIL_PATH, "
                            + "PHIS_FILE_NM, LOGI_FILE_NM, FILE_EXT, FILE_SIZE, PGM_ID, PATH_KEY) VALUES (?,?,?,?,?,?,?,?,?,?)",
                            regNo, newNo, root.toString(), detail, regNo, r.get("LOGI_FILE_NM"),
                            r.get("FILE_EXT"), r.get("FILE_SIZE"), r.get("PGM_ID"), r.get("PATH_KEY"));
                    regs.add(regNo);
                }
            } catch (IOException e) {
                rollbackReject(copied, regs);
                throw e;
            } catch (RuntimeException e) {
                rollbackReject(copied, regs);
                throw e;
            }
            return newNo;
        }

        private void rollbackReject(List<Path> copied, List<String> regs) {
            for (String reg : regs) {
                jdbc.update("DELETE FROM ATTACH_FILE WHERE FILE_REG_NO = ?", reg);
            }
            for (Path p : copied) {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignore) {
                    // best effort
                }
            }
        }

        /* ---------- delete ---------- */

        /**
         * deletes only rows that belong to the given fileMngNo; returns how many were
         * removed
         */
        public int delete(String fileMngNo, List<String> fileRegNos) throws IOException {
            int n = 0;
            for (String regNo : fileRegNos) {
                Map<String, Object> row = find(fileMngNo, regNo.trim());
                if (row == null) {
                    continue;
                }
                jdbc.update("DELETE FROM ATTACH_FILE WHERE FILE_REG_NO = ?", row.get("FILE_REG_NO"));
                Files.deleteIfExists(physicalPath(row));
                n++;
            }
            return n;
        }

        private static String cleanName(String name) {
            String n = name == null ? "file" : name;
            n = n.replace('\\', '/');
            n = n.substring(n.lastIndexOf('/') + 1).trim(); // strip any client path
            return n.isEmpty() ? "file" : n;
        }
    }

    // ======================================================================
    /**
     * Endpoints used by fileuploader.js.
     * Errors are returned as HTTP 200 {ErrorCode:-1, ErrorMsg:...} (same contract
     * as the original uploadFile.do).
     */
    @Controller
    public static class FileController {

        private final AttachFileService service;

        @Autowired
        public FileController(AttachFileService service) {
            this.service = service;
        }

        /**
         * Auto-upload target.
         * - fileMngNo empty -> create ONE new number for this request and return it.
         * - fileMngNo given -> attach the file(s) to it.
         */
        @RequestMapping(value = "/core/file/uploadFile.do", method = RequestMethod.POST)
        @ResponseBody
        public Map<String, Object> upload(MultipartRequest request,
                @RequestParam(value = "mode", defaultValue = "C") String mode,
                @RequestParam("pathKey") String pathKey,
                @RequestParam(value = "pgmId", required = false, defaultValue = "") String pgmId,
                @RequestParam(value = "fileMngNo", required = false) String fileMngNo) {
            Map<String, Object> res = new LinkedHashMap<String, Object>();
            try {
                if (!"C".equals(mode)) {
                    throw new IllegalArgumentException("Unsupported mode");
                }
                String mngNo = AttachFileService.cleanMngNo(fileMngNo);

                List<MultipartFile> files = new ArrayList<MultipartFile>();
                for (MultipartFile f : request.getFileMap().values()) {
                    if (f != null && !f.isEmpty()) {
                        files.add(f);
                    }
                }
                if (files.isEmpty()) {
                    throw new IllegalArgumentException("No file received");
                }

                if (mngNo.isEmpty()) {
                    mngNo = service.newFileMngNo(); // new number, once per request
                }
                List<Map<String, Object>> saved = new ArrayList<Map<String, Object>>();
                for (MultipartFile f : files) {
                    saved.add(service.save(f, mngNo, pathKey, pgmId));
                }
                res.put("ErrorCode", 0);
                res.put("FILE_RESULT", saved.get(saved.size() - 1)); // carries FILE_MNG_NO
                res.put("FILE_RESULT_LIST", saved);
            } catch (Exception e) {
                res.clear();
                res.put("ErrorCode", -1);
                res.put("ErrorMsg", "Failed to upload: " + e.getMessage());
            }
            return res;
        }

        @RequestMapping(value = "/core/file/listFile.do", method = RequestMethod.GET)
        @ResponseBody
        public Map<String, Object> list(@RequestParam(value = "fileMngNo", required = false) String fileMngNo,
                @RequestParam(value = "pathKey", required = false) String pathKey) {
            Map<String, Object> res = new LinkedHashMap<String, Object>();
            try {
                String mngNo = AttachFileService.cleanMngNo(fileMngNo);
                res.put("ErrorCode", 0);
                res.put("DS_FILE_UPLOAD_LIST",
                        mngNo.isEmpty() ? new ArrayList<Object>() : service.list(mngNo, pathKey));
            } catch (Exception e) {
                res.clear();
                res.put("ErrorCode", -1);
                res.put("ErrorMsg", e.getMessage());
            }
            return res;
        }

        /** List of all file manager numbers (with file count / total size). */
        @RequestMapping(value = "/core/file/listMngNo.do", method = RequestMethod.GET)
        @ResponseBody
        public Map<String, Object> listMngNo() {
            Map<String, Object> res = new LinkedHashMap<String, Object>();
            try {
                res.put("ErrorCode", 0);
                res.put("DS_MNG_NO_LIST", service.listMngNos());
            } catch (Exception e) {
                res.clear();
                res.put("ErrorCode", -1);
                res.put("ErrorMsg", e.getMessage());
            }
            return res;
        }

        @RequestMapping(value = "/core/file/deleteFile.do", method = RequestMethod.POST)
        @ResponseBody
        public Map<String, Object> delete(@RequestParam("fileMngNo") String fileMngNo,
                @RequestParam("fileRegNos") String fileRegNos) {
            Map<String, Object> res = new LinkedHashMap<String, Object>();
            try {
                String mngNo = AttachFileService.cleanMngNo(fileMngNo);
                if (mngNo.isEmpty()) {
                    throw new IllegalArgumentException("fileMngNo required");
                }
                int n = service.delete(mngNo, Arrays.asList(fileRegNos.split(",")));
                res.put("ErrorCode", 0);
                res.put("DELETED", n);
            } catch (Exception e) {
                res.clear();
                res.put("ErrorCode", -1);
                res.put("ErrorMsg", "Failed to delete: " + e.getMessage());
            }
            return res;
        }

        /**
         * Reject: copy all files of fileMngNo into a NEW fileMngNo and return it with
         * its file list.
         */
        @RequestMapping(value = "/core/file/rejectFile.do", method = RequestMethod.POST)
        @ResponseBody
        public Map<String, Object> reject(@RequestParam("fileMngNo") String fileMngNo) {
            Map<String, Object> res = new LinkedHashMap<String, Object>();
            try {
                String oldNo = AttachFileService.cleanMngNo(fileMngNo);
                if (oldNo.isEmpty()) {
                    throw new IllegalArgumentException("fileMngNo required");
                }
                String newNo = service.reject(oldNo);
                res.put("ErrorCode", 0);
                res.put("OLD_FILE_MNG_NO", oldNo);
                res.put("FILE_MNG_NO", newNo);
                res.put("DS_FILE_UPLOAD_LIST", service.list(newNo, null));
            } catch (Exception e) {
                res.clear();
                res.put("ErrorCode", -1);
                res.put("ErrorMsg", "Failed to reject: " + e.getMessage());
            }
            return res;
        }

        @RequestMapping(value = "/core/file/downloadFile.do", method = RequestMethod.GET)
        public ResponseEntity<Resource> download(@RequestParam("fileMngNo") String fileMngNo,
                @RequestParam("fileRegNo") String fileRegNo) throws Exception {
            String mngNo = AttachFileService.cleanMngNo(fileMngNo);
            Map<String, Object> row = mngNo.isEmpty() ? null : service.find(mngNo, fileRegNo);
            if (row == null) {
                return ResponseEntity.notFound().build();
            }
            Path p = service.physicalPath(row);
            if (!Files.isRegularFile(p)) {
                return ResponseEntity.notFound().build();
            }
            // RFC 5987 filename*, understood by IE 9+ and all current browsers
            String encName = URLEncoder.encode(String.valueOf(row.get("LOGI_FILE_NM")), "UTF-8").replace("+", "%20");
            return ResponseEntity.ok()
                    .header("Content-Disposition", "attachment; filename*=UTF-8''" + encName)
                    .header("X-Content-Type-Options", "nosniff")
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .contentLength(Files.size(p))
                    .body(new FileSystemResource(p.toFile()));
        }
    }

    // ======================================================================
    /**
     * Page SAF0101: a record that owns two upload controls (evidence / action
     * evidence).
     */
    @Controller
    public static class PageController {

        private final JdbcTemplate jdbc;

        @Autowired
        public PageController(JdbcTemplate jdbc) {
            this.jdbc = jdbc;
        }

        @RequestMapping(value = "/", method = RequestMethod.GET)
        public String home() {
            return "redirect:/saf0101";
        }

        @RequestMapping(value = "/saf0101", method = RequestMethod.GET)
        public String form(@RequestParam(value = "id", required = false) Long id, Model model) {
            Map<String, Object> vo = new HashMap<String, Object>();
            vo.put("id", "");
            vo.put("title", "");
            vo.put("fileMngNo", "");
            if (id != null) {
                List<Map<String, Object>> rows = jdbc.queryForList(
                        "SELECT ID, TITLE, FILE_MNG_NO FROM SAF_RECORD WHERE ID = ?", id);
                if (!rows.isEmpty()) {
                    Map<String, Object> r = rows.get(0);
                    vo.put("id", r.get("ID"));
                    vo.put("title", nz(r.get("TITLE")));
                    vo.put("fileMngNo", nz(r.get("FILE_MNG_NO")));
                }
            }
            model.addAttribute("vo", vo);
            return "saf0101";
        }

        @RequestMapping(value = "/saf0101/save", method = RequestMethod.POST)
        public String save(@RequestParam(value = "id", required = false) Long id,
                @RequestParam(value = "title", defaultValue = "") String title,
                @RequestParam(value = "fileMngNo", defaultValue = "") String fileMngNo) {
            String no = AttachFileService.cleanMngNo(fileMngNo);
            if (id == null) {
                jdbc.update("INSERT INTO SAF_RECORD (TITLE, FILE_MNG_NO) VALUES (?,?)",
                        title, no);
                id = jdbc.queryForObject("SELECT MAX(ID) FROM SAF_RECORD", Long.class);
            } else {
                jdbc.update("UPDATE SAF_RECORD SET TITLE=?, FILE_MNG_NO=? WHERE ID=?",
                        title, no, id);
            }
            return "redirect:/saf0101?id=" + id;
        }

        private static String nz(Object o) {
            return o == null ? "" : String.valueOf(o);
        }
    }

    // ======================================================================
    /**
     * Upload size/limit failures happen before the controller runs; keep the same
     * JSON contract.
     * (MultipartException is also the parent of MaxUploadSizeExceededException.)
     */
    @ControllerAdvice
    public static class UploadErrorAdvice {

        @ExceptionHandler(MultipartException.class)
        @ResponseBody
        public Map<String, Object> uploadFailed(MultipartException e) {
            Map<String, Object> res = new LinkedHashMap<String, Object>();
            res.put("ErrorCode", -1);
            res.put("ErrorMsg", "Upload failed - the file may be too large.");
            return res;
        }
    }

}
