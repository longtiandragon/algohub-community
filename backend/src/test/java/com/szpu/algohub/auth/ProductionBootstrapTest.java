package com.szpu.algohub.auth;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import org.junit.jupiter.api.Test;
import com.szpu.algohub.config.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

class ProductionBootstrapTest {
    @org.junit.jupiter.api.io.TempDir java.nio.file.Path temp;
    private AppProperties props() {
        var p = new AppProperties(); p.getSecurity().setProduction(true);
        p.getSecurity().setSecureCookie(true); p.getSecurity().setRequireExistingKeys(true); return p;
    }
    @Test void defaultPasswordCannotBootstrapProduction() {
        var mapper = mock(AppUserMapper.class);
        var runner = new BootstrapRunner(props(),mapper,new BCryptPasswordEncoder(4));
        assertThatThrownBy(() -> runner.run(null)).isInstanceOf(IllegalStateException.class);
        verify(mapper,never()).insert(any());
    }
    @Test void tenCharacterPasswordCanBootstrapProduction() {
        var p = props(); p.getBootstrap().setAdminPassword("1234567890");
        var mapper = mock(AppUserMapper.class);
        new BootstrapRunner(p, mapper, new BCryptPasswordEncoder(4)).run(null);
        verify(mapper).insert(any());
    }
    @Test void importedDefaultPasswordIsRejectedInsteadOfOverwritten() {
        var p = props(); p.getBootstrap().setAdminPassword("Random-Production-Password-938");
        var encoder = new BCryptPasswordEncoder(4);
        var user = new AppUser(); user.setPasswordHash(encoder.encode("admin12345"));
        var mapper = mock(AppUserMapper.class); when(mapper.findByUsername("admin")).thenReturn(user);
        assertThatThrownBy(() -> new BootstrapRunner(p,mapper,encoder).run(null))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("已有管理员");
        verify(mapper,never()).insert(any());
    }
    @Test void missingProductionKeysNeverRegenerate() {
        var p = props();
        p.getCredential().setKeyFile(java.nio.file.Path.of("target/absent-production-key").toAbsolutePath().toString());
        p.getSecurity().setJwtSecretFile(java.nio.file.Path.of("target/absent-production-jwt").toAbsolutePath().toString());
        assertThatThrownBy(() -> new com.szpu.algohub.account.CredentialCipher(p)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new JwtService(p)).isInstanceOf(IllegalStateException.class);
        assertThat(java.nio.file.Path.of(p.getCredential().getKeyFile())).doesNotExist();
    }
    @Test void copiedCredentialKeyDecryptsExistingCiphertext() throws Exception {
        var original = new AppProperties();
        var oldFile = temp.resolve("old/credential-key.txt");
        original.getCredential().setKeyFile(oldFile.toString());
        String encrypted = new com.szpu.algohub.account.CredentialCipher(original).encrypt("synthetic-test-cookie");
        var newFile = temp.resolve("production/credential-key.txt");
        java.nio.file.Files.createDirectories(newFile.getParent());
        java.nio.file.Files.copy(oldFile,newFile);
        var migrated = props(); migrated.getCredential().setKeyFile(newFile.toString());
        assertThat(new com.szpu.algohub.account.CredentialCipher(migrated).decrypt(encrypted))
                .isEqualTo("synthetic-test-cookie");
    }
}
