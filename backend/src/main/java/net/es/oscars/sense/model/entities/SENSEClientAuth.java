package net.es.oscars.sense.model.entities;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.*;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import javax.persistence.*;
import java.util.HashSet;
import java.util.Set;

@Data
// @Entity
// @Table(name = "senseclientauth")
@NoArgsConstructor
@AllArgsConstructor
public class SENSEClientAuth implements UserDetails {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @JsonIgnore
    private Long id;

    @NonNull
    @Column(unique = true)
    private String cn;

    @Override
    @JsonIgnore
    public Set<GrantedAuthority> getAuthorities() {
        Set<GrantedAuthority> res = new HashSet<>();
        res.add(new SimpleGrantedAuthority("SENSE_CLIENT"));
        return res;
    }

    @Override
    @JsonIgnore
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    @JsonIgnore
    public boolean isAccountNonLocked() {
        return true;

    }

    @Override
    @JsonIgnore
    public boolean isCredentialsNonExpired() {
        return true;

    }

    @Override
    @JsonIgnore
    public boolean isEnabled() {
        return true;

    }

    @Override
    public String getPassword() {
        return null;
    }

    @Override
    public String getUsername() {
        return cn;
    }
}
