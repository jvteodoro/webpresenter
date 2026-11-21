// Development settings. Production values are provided at deploy time.
export const environment = {
  apiUrl: 'http://localhost:8090',
  keycloak: {
    url: 'http://localhost:8180',
    realm: 'webpresenter',
    clientId: 'webpresenter-web',
  },
};
