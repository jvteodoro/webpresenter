// Production: the web app, the API and Keycloak (/auth) share one origin behind the reverse proxy.
export const environment = {
  apiUrl: '',
  keycloak: {
    url: `${window.location.origin}/auth`,
    realm: 'webpresenter',
    clientId: 'webpresenter-web',
  },
};
