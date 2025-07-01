package xdm.app.models;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class AuthenticationInfo {
  private String userName;
  private String password;
}
