// Development settings. Production values are provided at deploy time.
export const environment = {
  apiUrl: 'http://localhost:8090',
  oidc: {
    issuer: 'http://localhost:8180/realms/webpresenter',
    clientId: 'webpresenter-web',
  },
};
