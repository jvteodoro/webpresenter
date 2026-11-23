# Announces the server on the LAN (mDNS name and UDP broadcast); see beacon.py.
FROM python:3.12.12-slim-trixie
RUN pip install --no-cache-dir "zeroconf==0.148.0" "ifaddr==0.2.0"
COPY beacon.py /beacon.py
USER nobody
CMD ["python", "-u", "/beacon.py"]
