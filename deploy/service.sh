#!/bin/bash
#
# Script running the server.
# 

source ../config.properties

uphosts=0
masterip=0

log="debugservice"
deploylog="debugdeploy"
stoplog="debugstop"

function startService()
{
    ip=$1
    echo "Trying $ip ..."
    if [[ $uphosts > 0 ]]; then
        ./runSlave $ip $masterip &>>$log &
        pid=$!
        sleep 2
        ./runCheckServer $ip
        result=$?
        if [[ $result == 0 ]]; then
	   #we've got a new host
           uphosts=$(($uphosts+1))
           echo "    Slave $ip is ready to serve."
        else
           echo "    Server $ip failed to init"
           disown $pid
           kill -9 $pid &>/dev/null
        fi
        
    else
        ./runMaster $ip &>>$log &
        pid=$!
        sleep 2
        ./runCheckServer $ip
        result=$?
        if [[ $result == 1 ]]; then
            #it's an error, kill ssh
            echo "    Server $ip failed to init."
            disown $pid
            kill -9 $pid &>/dev/null
        else
	    #it's a new master!
            masterip=$ip
            uphosts=$(($uphosts+1))
	    echo "    Master $ip is ready to serve"
        fi
    fi
}

function deploy()
{
    ip=$1
    echo "Deploying to $ip ..."
    timeout 10 ./deployToServer $ip &>>$deploylog
    result=$?
    if [[ $result == 124 ]]; then
	echo "    Failed to deploy to $ip"
    else
        echo "    Deploy successfull for $ip"
    fi
}

function stop()
{
    ip=$1
    echo "Stopping $ip ..."
    timeout 3 ./killemAll $ip &>>$stoplog
    if [[ $result == 124 ]]; then
	echo "    Failed to stop service for $ip"
    else
        echo "    Service stopped for $ip"
    fi
}

function haltvm()
{
    ip=$1
    echo "Halting vm $ip ..."
    timeout 3 ./haltvm $ip &>>$stoplog
    if [[ $result == 124 ]]; then
	echo "    Failed to halt vm for $ip"
    else
        echo "    Halt success for $ip"
    fi
}

echo "DFS Service script"
echo 
echo "Checking for servers..."
echo 

iplist=''
for i in {1..9}; do
   varName=server0$i
   value=${!varName}
   if [[ -n $value ]]; then
      startr=`echo $value | awk -F"-" '{print $1}'`
      endr=`echo $value | awk -F"-" '{print $2}'`
      if [[ ! '' == $endr ]]; then
          #it's ip range
          for ip in `./iprange.sh $startr $endr`; do
             iplist="$iplist $ip"
          done
      else
          #it's plain ip
          ip=$startr
          iplist="$iplist $ip"
      fi
   fi
done
#got ip addresses

ipWithSSH=`nmap -p22 -oG - $iplist | awk '/open/{print $2}' | tr "\\n" " "`

echo "List of IPs with SSH port open:"
echo "$ipWithSSH"
echo

if [[ $1 == 'start' ]]; then
    echo "----------------------------------" >>$log
    echo "Start: log for `date`" >>$log
    echo "----------------------------------" >>$log 

    for ip in $ipWithSSH; do
        startService $ip
    done
    
    echo
    if [[ $uphosts > 1 ]]; then
        echo "Service started succesfully."
        echo "Master IP: $masterip"
        echo "There are $uphosts servers running"
        echo
    fi	
fi

if [[ $1 == 'deploy' ]]; then
    echo "----------------------------------" >>$deploylog
    echo "Deploy: log for `date`" >>$deploylog
    echo >>$deploylog

    for ip in $ipWithSSH; do
        deploy $ip
    done
fi

if [[ $1 == 'stop' ]]; then
    echo "----------------------------------" >>$stoplog
    echo "Stop: log for `date`" >>$stoplog
    echo >>$stoplog

    for ip in $ipWithSSH; do
        stop $ip
    done
fi


if [[ $1 == 'haltvm' ]]; then
    echo "----------------------------------" >>$stoplog
    echo "Halt: log for `date`" >>$stoplog
    echo >>$stoplog

    for ip in $ipWithSSH; do
        haltvm $ip
    done
fi
