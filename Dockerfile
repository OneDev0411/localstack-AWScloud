FROM maven:alpine

MAINTAINER Waldemar Hummer (whummer@atlassian.com)

RUN apk update && \
	apk add --update autoconf automake build-base ca-certificates git libffi-dev libtool make nodejs openssl openssl-dev python python-dev py-pip zip && \
	update-ca-certificates

# set workdir
RUN mkdir -p /opt/code/localstack
WORKDIR /opt/code/localstack/

# init environment and cache some dependencies
RUN wget -O /tmp/localstack.es.zip https://download.elastic.co/elasticsearch/release/org/elasticsearch/distribution/zip/elasticsearch/2.3.3/elasticsearch-2.3.3.zip
ADD requirements.txt .
RUN (pip install --upgrade pip) && \
	(test `which virtualenv` || pip install virtualenv || sudo pip install virtualenv) && \
	(virtualenv .testvenv && source .testvenv/bin/activate && pip install -r requirements.txt && rm -rf .testvenv)

# add files required to run make install
ADD Makefile .
ADD localstack/__init__.py localstack/__init__.py
ADD localstack/utils/__init__.py localstack/utils/__init__.py
ADD localstack/utils/kinesis/__init__.py localstack/utils/kinesis/__init__.py
ADD localstack/utils/kinesis/ localstack/utils/kinesis/
ADD localstack/utils/common.py localstack/utils/common.py
ADD localstack/constants.py localstack/constants.py

# install dependencies
RUN make install

# TODO: temporary change to fix error "Cannot find module 'semver'" when running npm
RUN rm -rf /usr/lib/node_modules && apk del nodejs && apk add --update nodejs && npm install npm@latest -g

# add rest of the code
ADD localstack/ localstack/

# initialize installation (downloads remaining dependencies)
RUN make init

# fix some permissions
RUN mkdir -p /.npm && chmod -R 777 /.npm && \
	chmod 777 . && \
	chmod -R 777 localstack/infra/elasticsearch/data

# assign random user id
USER 24624336
ENV USER docker

# expose service ports
EXPOSE 4567-4577

# run tests (to verify the build before pushing the image)
ADD tests/ tests/
RUN make test

# define entrypoint/command
ENTRYPOINT ["make"]
CMD ["infra"]
