package com.aikax28.scp.client;

import com.google.common.collect.Lists;
import com.jcraft.jsch.Channel;
import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Base64;
import java.util.List;
import java.util.Properties;
import javax.annotation.Nonnull;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;

/**
 *
 * @author aikax28
 * @see
 * <a href="http://www.jcraft.com/jsch/examples/ScpFrom.java.html">ScpFrom</a>
 */
public class ScpClient {

    /**
     *
     * @param info
     * @param rfile remote
     * @param lfile local file or directory
     * @return
     */
    public List<File> getFile(@Nonnull final ScpConnectionInfo info,
                              @Nonnull final String rfile,
                              @Nonnull final String lfile) {
        List<File> files = Lists.newArrayList();

        Properties config = new Properties();
        config.put("StrictHostKeyChecking", "no");

        FileOutputStream fos = null;
        Session session = null;
        try {

            String prefix = null;
            if (new File(lfile).isDirectory()) {
                prefix = lfile + File.separator;
            }

            JSch jsch = new JSch();
            session = jsch.getSession(info.getUsername(),
                                      info.getHostname(),
                                      info.getPort());

            jsch.addIdentity(info.getRsaPrivateKeyFormatBase64(),
                             Base64.getDecoder().decode(info.getRsaPrivateKeyFormatBase64()),
                             null,
                             StringUtils.isBlank(info.getPassword()) ? null : info.getPassword().getBytes());

            session.setConfig(config);
            session.connect();

            // exec 'scp -f rfile' remotely
//            String _rfile = rfile.replace("'", "'\\''");
//            _rfile = "'" + _rfile + "'";
            String command = "scp -f " + rfile;
            Channel channel = session.openChannel("exec");
            ((ChannelExec) channel).setCommand(command);

            // get I/O streams for remote scp
            OutputStream out = channel.getOutputStream();
            InputStream in = channel.getInputStream();

            channel.connect();

            byte[] buf = new byte[1024];

            // send '\0'
            buf[0] = 0;
            out.write(buf, 0, 1);
            out.flush();

            while (true) {
                int c = checkAck(in);
                if (c != 'C') {
                    break;
                }

                // read '0644 '
                in.read(buf, 0, 5);

                long filesize = 0L;
                while (true) {
                    if (in.read(buf, 0, 1) < 0) {
                        // error
                        break;
                    }
                    if (buf[0] == ' ') {
                        break;
                    }
                    filesize = filesize * 10L + (long) (buf[0] - '0');
                }

                String file = null;
                for (int i = 0;; i++) {
                    in.read(buf, i, 1);
                    if (buf[i] == (byte) 0x0a) {
                        file = new String(buf, 0, i);
                        break;
                    }
                }

                //System.out.println("filesize="+filesize+", file="+file);
                // send '\0'
                buf[0] = 0;
                out.write(buf, 0, 1);
                out.flush();

                // read a content of lfile
                File outputFile = new File(prefix == null ? lfile : prefix + file);
                fos = new FileOutputStream(outputFile);
                int foo;
                while (true) {
                    foo = buf.length < filesize ? buf.length : (int) filesize;
                    foo = in.read(buf, 0, foo);
                    if (foo < 0) {
                        // error
                        break;
                    }
                    fos.write(buf, 0, foo);
                    filesize -= foo;
                    if (filesize == 0L) {
                        break;
                    }
                }
                fos.close();
                fos = null;
                files.add(outputFile);

                if (checkAck(in) != 0) {
                    throw new RuntimeException("no ack");
                }

                // send '\0'
                buf[0] = 0;
                out.write(buf, 0, 1);
                out.flush();
            }

        } catch (JSchException | IOException e) {
            throw new RuntimeException(e);
        } finally {
            try {
                IOUtils.closeQuietly(fos);
            } catch (Exception ex) {
            }
            try {
                if (session != null) {
                    session.disconnect();
                }
            } catch (Exception ex) {
            }
        }
        return files;
    }

    static int checkAck(InputStream in) throws IOException {
        int b = in.read();
        // b may be 0 for success,
        //          1 for error,
        //          2 for fatal error,
        //          -1
        if (b == 0) {
            return b;
        }
        if (b == -1) {
            return b;
        }

        if (b == 1 || b == 2) {
            StringBuilder sb = new StringBuilder();
            int c;
            do {
                c = in.read();
                sb.append((char) c);
            } while (c != '\n');
            if (b == 1) { // error
                throw new RuntimeException(sb.toString());
            }
            if (b == 2) { // fatal error
                throw new RuntimeException(sb.toString());
            }
        }
        return b;
    }

    @lombok.AllArgsConstructor
    @lombok.NoArgsConstructor
    @lombok.Data
    @lombok.Builder
    public static class ScpConnectionInfo {

        private String username;

        private String hostname;

        private Integer port;

        private String rsaPrivateKeyFormatBase64;

        private String password;

    }
}
