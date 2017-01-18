#!/bin/bash
export home=$HOME

hosts=("54.148.240.165" "54.187.178.248")


bash local_deploy.sh


#  for i in range(0, len(hosts)):
#    deploy_to_server(hosts[i])

def deploy_to_server(host):
  command = 'rsync -rvlLt --delete -e "ssh -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no -i  ' + home + '/amazonkeys/dale-aws2.pem" ' + home + '/database ' + 'ubuntu@' + host + ':'
  print "Deploying Code: " + command
  split_command = shlex.split(command)
  ret = call(split_command)
  if ret == 0:
    print "Deployed Code successfully to " + host
  else:
    print "Deploy Code failed to " + host + ", code=" + unicode(ret)


def local_deploy():

if __name__=="__main__":
  main()

