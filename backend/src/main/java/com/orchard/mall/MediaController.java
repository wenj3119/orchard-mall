package com.orchard.mall;

import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.security.core.context.SecurityContextHolder;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@RestController
public class MediaController {
    private final JdbcTemplate db;
    private final ObjectStorage storage;
    public MediaController(JdbcTemplate db,ObjectStorage storage) { this.db=db;this.storage=storage; }
    @PostMapping(value="/api/admin/media",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String,Object> upload(@RequestParam MultipartFile file) throws Exception {
        String type=file.getContentType();
        if(file.isEmpty() || file.getSize()>10_000_000 || !Set.of("image/jpeg","image/png","image/webp").contains(type))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Only JPEG, PNG or WebP images up to 10 MB");
        byte[] head;
        try(var stream=file.getInputStream()) { head=stream.readNBytes(12); }
        boolean jpeg=head.length>=3 && (head[0]&255)==0xff && (head[1]&255)==0xd8 && (head[2]&255)==0xff;
        boolean png=head.length>=8 && (head[0]&255)==0x89 && head[1]=='P' && head[2]=='N' && head[3]=='G';
        boolean webp=head.length>=12 && head[0]=='R' && head[1]=='I' && head[2]=='F' && head[3]=='F' && head[8]=='W' && head[9]=='E' && head[10]=='B' && head[11]=='P';
        if(!(type.equals("image/jpeg")&&jpeg || type.equals("image/png")&&png || type.equals("image/webp")&&webp))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Image content does not match file type");
        String key="products/"+UUID.randomUUID();
        storage.put(key,file.getInputStream(),file.getSize(),type);
        var holder=new GeneratedKeyHolder();
        db.update(c -> {
            var ps=c.prepareStatement("INSERT INTO media_object (object_key,content_type) VALUES (?,?)",new String[]{"id"});
            ps.setString(1,key);ps.setString(2,type);return ps;
        },holder);
        long id=holder.getKey().longValue();
        return Map.of("id",id,"url","/api/media/"+id);
    }
    @GetMapping("/api/media/{id}")
    public ResponseEntity<byte[]> get(@PathVariable long id) throws Exception {
        var found=db.query("SELECT object_key,content_type FROM media_object WHERE id=?",
            (rs,n)->Map.of("key",rs.getString(1),"type",rs.getString(2)),id);
        if(found.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        var auth=SecurityContextHolder.getContext().getAuthentication();
        boolean admin=auth!=null && auth.isAuthenticated() && auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
        Integer publicLinks=db.queryForObject("SELECT COUNT(*) FROM product_image pi JOIN product p ON p.id=pi.product_id WHERE pi.media_id=? AND p.published=TRUE",Integer.class,id);
        Integer logoLinks=db.queryForObject("SELECT COUNT(*) FROM store_settings WHERE logo_url=?",Integer.class,"/api/media/"+id);
        if(!admin && (publicLinks==null || publicLinks==0) && (logoLinks==null || logoLinks==0))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        try(var content=storage.get(found.getFirst().get("key")).stream()) {
            return ResponseEntity.ok().contentType(MediaType.parseMediaType(found.getFirst().get("type")))
                .cacheControl(CacheControl.maxAge(java.time.Duration.ofDays(7))).body(content.readAllBytes());
        }
    }
}
