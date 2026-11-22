/**
 * Processing jobs: queued by people, executed by Python workers through RabbitMQ (YouTube import
 * with yt-dlp, vocal separation with Demucs), results registered in the library and songs.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Jobs")
package webpresenter.server.jobs;
